package ar.scraper.db;

import ar.scraper.aggregator.normalize.RubroResolver;
import ar.scraper.aggregator.normalize.SiteClassification;
import ar.scraper.cron.CronExecution;
import ar.scraper.cron.CronJob;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * PostgreSQL persistence layer (decouple-services-postgres, Batch 1).
 *
 * Conexión: pool HikariCP administrado por Spring Boot ({@code DataSource}
 * inyectado), apuntando a {@code DATABASE_URL} (env-only, design D6). Cada
 * método de este servicio toma prestada una conexión del pool por llamada
 * (try-with-resources) — ya NO existe una conexión única compartida de
 * escritura/lectura ni el {@code writeLock}/{@code readLock} que la
 * serializaban: Postgres MVCC permite lectores y escritor conviviendo sin
 * el lock-dance que SQLite necesitaba (design D1). El schema (15 tablas +
 * funciones {@code sp_upsert_run}/{@code sp_soft_delete_ausentes}) lo
 * administra Flyway ({@code db/migration/V1__baseline.sql}) — reemplaza el
 * bootstrap runtime que existía en {@code initEn()}/{@code crearTablas()}/
 * {@code migrarColumna()} (removidos, design D3).
 *
 * Upsert logic (preservada exactamente, ahora ejecutada server-side vía
 * {@code sp_upsert_run}, design D2):
 *   - Producto nuevo  → INSERT + historial
 *   - Precio igual    → UPDATE touched_at solamente
 *   - Precio cambió   → UPDATE precio + INSERT en precio_historico
 *   - No apareció     → soft-delete (activo=0) vía {@code sp_soft_delete_ausentes}
 */
@Service
public class DatabaseService {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_HIST_DAYS = 90;
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DataSource dataSource;
    // Stateless (no injected dependencies of its own) — instantiated directly so this
    // constructor's shape (DataSource only) stays unchanged for the ~18 existing test
    // call sites (manual-classification-lock Phase 3, zero call-site churn).
    private final RubroResolver rubroResolver = new RubroResolver();

    /**
     * Per-aggregate repository holding the {@code cron_jobs}/{@code cron_executions}
     * bodies (backlog A3). Built here rather than injected so this constructor's
     * shape (DataSource only) stays unchanged for the existing test call sites.
     */
    private final CronRepository cronRepository;
    private final PresetRepository presetRepository;
    private final FavoritosRepository favoritosRepository;
    private final FeedbackRepository feedbackRepository;
    private final SavedOutfitsRepository savedOutfitsRepository;
    private final MlOutputRepository mlOutputRepository;
    private final HistorialRepository historialRepository;
    private final SitiosRepository sitiosRepository;
    private final CategoriaStatsRepository categoriaStatsRepository;
    private final PreciosExternosRepository preciosExternosRepository;

    public DatabaseService(DataSource dataSource) {
        this.dataSource = dataSource;
        this.cronRepository = new CronRepository(dataSource);
        this.presetRepository = new PresetRepository(dataSource);
        this.favoritosRepository = new FavoritosRepository(dataSource);
        this.feedbackRepository = new FeedbackRepository(dataSource);
        this.savedOutfitsRepository = new SavedOutfitsRepository(dataSource);
        this.mlOutputRepository = new MlOutputRepository(dataSource);
        this.historialRepository = new HistorialRepository(dataSource);
        this.sitiosRepository = new SitiosRepository(dataSource);
        this.categoriaStatsRepository = new CategoriaStatsRepository(dataSource);
        this.preciosExternosRepository = new PreciosExternosRepository(dataSource);
    }

    @PostConstruct
    void init() {
        try {
            presetRepository.seedPresetIlustrativoSiVacio();
            LOG.info("[DB] Conectado (pool HikariCP sobre {})", safeDescribeDataSource());
        } catch (Exception e) {
            LOG.error("[DB] Error en el seed inicial: {}", e.getMessage(), e);
        }
    }

    private String safeDescribeDataSource() {
        try {
            return dataSource.getClass().getSimpleName();
        } catch (Exception e) {
            return "DataSource";
        }
    }

    /**
     * Cuenta las filas cacheadas en {@code image_embeddings} (fashion-image-
     * classification PR6, T6.3/T6.4) — respalda {@code embeddingsCount} en
     * {@code GET /api/ml/estado} para reportar cobertura del índice visual
     * frente al total de productos activos.
     */
    public long contarEmbeddings() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM image_embeddings")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            LOG.error("[DB] Error al contar image_embeddings", e);
            return 0L;
        }
    }

    // ─── Presets de financiación. Bodies in PresetRepository (backlog A3);
    // this class keeps the public surface and delegates. The Preset record
    // stays HERE: callers and tests name it DatabaseService.Preset.
    // ─────────────────────────────────────────────────────────────────────

    public record Preset(int id, String label, double recargoPct, int cuotas, boolean activo) {}

    public List<Preset> listarPresets() {
        return presetRepository.listarPresets();
    }

    public Optional<Preset> cargarPresetActivo() {
        return presetRepository.cargarPresetActivo();
    }

    /**
     * Crea un preset nuevo, inactivo por defecto. Retorna el id generado, o -1 en
     * error o si {@code cuotas}/{@code recargoPct} son inválidos (mismo criterio
     * que {@code FinanciacionCalculator.compute}: cuotas&gt;0 y recargoPct&gt;-100).
     */
    public int crearPreset(String label, double recargoPct, int cuotas) {
        return presetRepository.crearPreset(label, recargoPct, cuotas);
    }

    /**
     * Edita label/recargoPct/cuotas de un preset existente. No altera su estado activo.
     * Retorna {@code false} sin persistir si {@code cuotas}/{@code recargoPct} son
     * inválidos, o si ocurre un error.
     */
    public boolean editarPreset(int id, String label, double recargoPct, int cuotas) {
        return presetRepository.editarPreset(id, label, recargoPct, cuotas);
    }

    /**
     * Activa el preset {@code id} y desactiva todos los demás, de forma transaccional.
     * Retorna {@code false} (y revierte la desactivación) si {@code id} no existe.
     */
    public boolean activarPreset(int id) {
        return presetRepository.activarPreset(id);
    }

    /**
     * Elimina un preset. Si era el ÚNICO restante, recrea el preset ilustrativo
     * activo; si quedan otros, ninguno se auto-activa.
     *
     * @return {@code true} si el {@code id} existía y fue borrado.
     */
    public boolean eliminarPreset(int id) {
        return presetRepository.eliminarPreset(id);
    }

    // ─── Upsert de productos (write-path, design D2) ─────────────────────────

    /**
     * Aplica la lógica de merge al dataset completo de un scraping, delegando
     * la decisión de "cambió el precio" y el insert/upsert/historial a
     * {@code sp_upsert_run} (server-side, design D2) — ya NO hay una lectura
     * previa {@code getPreciosActuales()} en Java que pueda desincronizarse
     * con un writer concurrente. Retorna estadísticas: {nuevos, actualizados,
     * sinCambios, desactivados}.
     */
    public UpsertStats upsertProductos(List<Product> productos) {
        String now   = LocalDateTime.now().format(DT);
        String today = LocalDate.now().format(DATE);

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                String rowsJson = buildRowsJson(productos, now, today, true);

                int nuevos = 0, actualizados = 0, sinCambios = 0;
                try (PreparedStatement ps = c.prepareStatement("SELECT sp_upsert_run(?::jsonb, ?)")) {
                    ps.setString(1, rowsJson);
                    ps.setBoolean(2, true);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            JsonNode stats = MAPPER.readTree(rs.getString(1));
                            nuevos       = stats.path("nuevos").asInt(0);
                            actualizados = stats.path("actualizados").asInt(0);
                            sinCambios   = stats.path("sinCambios").asInt(0);
                        }
                    }
                }

                Set<String> urlsNuevoRun = new LinkedHashSet<>();
                for (Product p : productos) {
                    if (p.url() != null && !p.url().isBlank()) urlsNuevoRun.add(p.url());
                }
                int desactivados = softDeleteAusentes(c, urlsNuevoRun, now);

                purgarHistorialViejo(c);

                c.commit();

                LOG.info("[DB] Upsert: {} nuevos / {} precio cambió / {} sin cambio / {} desactivados",
                        nuevos, actualizados, sinCambios, desactivados);
                return new UpsertStats(nuevos, actualizados, sinCambios, desactivados);
            } catch (Exception e) {
                LOG.error("[DB] Error en upsert: {}", e.getMessage(), e);
                try { c.rollback(); } catch (Exception ignored) {}
                return new UpsertStats(0, 0, 0, 0);
            }
        } catch (SQLException e) {
            LOG.error("[DB] Error en upsert: {}", e.getMessage(), e);
            return new UpsertStats(0, 0, 0, 0);
        }
    }

    /**
     * Serializa el batch de productos al array JSON consumido por
     * {@code sp_upsert_run} — mismo set de columnas que antes armaba el
     * {@code PreparedStatement} Java, ahora empaquetado como filas jsonb.
     */
    private String buildRowsJson(List<Product> productos, String now, String fecha, boolean includeVisual)
            throws Exception {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Product p : productos) {
            if (p.url() == null || p.url().isBlank()) continue;
            ObjectNode row = arr.addObject();
            row.put("url", p.url());
            row.put("sitio", p.sitio());
            row.put("nombre", p.nombre());
            row.put("precio", p.precio());
            row.put("precioOrig", p.precioOriginal());
            row.put("imagenUrl", p.imagenUrl());
            row.put("categoria", p.categoria());
            row.put("genero", p.genero());
            row.put("talles", MAPPER.writeValueAsString(p.talles() != null ? p.talles() : List.of()));
            String badge = (p.ml() != null && p.ml().badges() != null)
                    ? String.join(",", p.ml().badges()) : "";
            row.put("mlBadge", badge);
            row.put("mlScore", p.ml() != null ? p.ml().scoreP() : 50);
            row.put("mlOferta", (p.ml() != null && p.ml().ofertaReal()) ? 1 : 0);
            row.put("mlTendencia", p.ml() != null ? p.ml().tendencia() : "");
            row.put("mlSegment", p.ml() != null ? p.ml().segment() : "standard");
            row.put("mlZscore", p.ml() != null ? p.ml().zScore() : 0.0);
            row.put("rubro", p.rubro() != null ? p.rubro() : "indumentaria");
            row.put("marca", p.marca() != null ? p.marca() : "");
            row.put("gymrat", p.gymrat() ? 1 : 0);
            row.put("marcaPremium", p.marcaPremium() ? 1 : 0);
            row.put("cantidadUnidades", p.cantidadUnidades());
            row.put("subCategoria", p.subCategoria() != null ? p.subCategoria() : "");

            if (includeVisual) {
                Product.VisualAttrs visual = p.visual() != null ? p.visual() : Product.VisualAttrs.EMPTY;
                row.put("fit", visual.fit() != null ? visual.fit() : "");
                row.put("estampado", visual.estampado() != null ? visual.estampado() : "");
                row.put("escote", visual.escote() != null ? visual.escote() : "");
                row.put("colorDominante", visual.colorDominante() != null ? visual.colorDominante() : "");
            }

            row.put("now", now);
            row.put("fecha", fecha);
        }
        return MAPPER.writeValueAsString(arr);
    }

    private int softDeleteAusentes(Connection c, Set<String> urlsPresentes, String now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT sp_soft_delete_ausentes(?, ?)")) {
            Array urlArray = c.createArrayOf("text", urlsPresentes.toArray());
            ps.setArray(1, urlArray);
            ps.setString(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void purgarHistorialViejo(Connection c) throws SQLException {
        String cutoff = LocalDate.now().minusDays(MAX_HIST_DAYS).format(DATE);
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM precio_historico WHERE fecha < ? " +
                "AND url NOT IN (SELECT url FROM favoritos)")) {
            ps.setString(1, cutoff);
            int deleted = ps.executeUpdate();
            if (deleted > 0) LOG.debug("[DB] Purged {} entradas historial > 90 dias", deleted);
        }
    }

    /**
     * Upsert parcial durante scraping progresivo.
     * NUNCA hace soft-delete — solo inserta/actualiza los productos dados.
     * El soft-delete lo hace upsertProductos() al final del run completo.
     * Columnas visuales excluidas a propósito (mismo motivo que antes: en
     * esta etapa del pipeline VisualAttrs todavía no está poblado).
     */
    public void upsertParcial(List<Product> productos) {
        if (productos == null || productos.isEmpty()) return;
        String now   = LocalDateTime.now().format(DT);
        String today = LocalDate.now().format(DATE);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                String rowsJson = buildRowsJson(productos, now, today, false);
                try (PreparedStatement ps = c.prepareStatement("SELECT sp_upsert_run(?::jsonb, ?)")) {
                    ps.setString(1, rowsJson);
                    ps.setBoolean(2, false);
                    ps.executeQuery().close();
                }
                c.commit();
            } catch (Exception e) {
                LOG.warn("[DB] Error en upsertParcial: {}", e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error en upsertParcial: {}", e.getMessage());
        }
    }

    // ─── Cargar productos ────────────────────────────────────────────────────

    public List<Product> cargarProductos() {
        List<Product> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT url,sitio,nombre,precio,precio_orig,imagen_url," +
                "categoria,genero,talles,ml_badge,ml_score,ml_oferta,ml_tendencia," +
                "ml_segment,ml_zscore,rubro,marca,gymrat,marca_premium,cantidad_unidades,sub_categoria," +
                "fit,estampado,escote,color_dominante " +
                "FROM productos WHERE activo=1 ORDER BY precio ASC")) {
            while (rs.next()) {
                result.add(productoDesdeFila(rs));
            }
            LOG.info("[DB] Cargados {} productos activos", result.size());
        } catch (Exception e) {
            LOG.error("[DB] Error cargando productos: {}", e.getMessage(), e);
        }
        return result;
    }

    /** Busca un producto por URL sin filtrar por `activo` (incluye descontinuados). */
    public java.util.Optional<Product> obtenerProducto(String url) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT url,sitio,nombre,precio,precio_orig,imagen_url," +
                "categoria,genero,talles,ml_badge,ml_score,ml_oferta,ml_tendencia," +
                "ml_segment,ml_zscore,rubro,marca,gymrat,marca_premium,cantidad_unidades,sub_categoria," +
                "fit,estampado,escote,color_dominante FROM productos WHERE url=?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return java.util.Optional.empty();
                return java.util.Optional.of(productoDesdeFila(rs));
            }
        } catch (Exception e) {
            LOG.error("[DB] Error obteniendo producto {}: {}", url, e.getMessage(), e);
            return java.util.Optional.empty();
        }
    }

    /**
     * Read-side of the manual classification lock (design D3/D4). One entry
     * per locked product, keyed by url — {@code ResultAggregator.aplicarBloqueos}
     * reads this ONCE per {@code agregar} call and applies it in memory before
     * ML scoring and again after stage-1b (the SQL guards in {@code sp_upsert_run}
     * are authoritative for persistence; this closes the in-memory
     * {@code lastResult} snapshot gap, design problem 3).
     */
    public Map<String, ClasificacionBloqueada> cargarClasificacionBloqueada() {
        Map<String, ClasificacionBloqueada> result = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT url,categoria,sub_categoria,marca,genero,rubro FROM productos "
                        + "WHERE bloqueado_por IS NOT NULL")) {
            while (rs.next()) {
                result.put(rs.getString("url"), new ClasificacionBloqueada(
                        rs.getString("categoria"),
                        rs.getString("sub_categoria"),
                        rs.getString("marca"),
                        rs.getString("genero"),
                        rs.getString("rubro")));
            }
        } catch (Exception e) {
            LOG.error("[DB] Error cargando clasificaciones bloqueadas: {}", e.getMessage(), e);
        }
        return result;
    }

    private Product productoDesdeFila(ResultSet rs) throws java.sql.SQLException {
        List<String> talles = List.of();
        try {
            JsonNode arr = MAPPER.readTree(rs.getString("talles"));
            if (arr.isArray()) {
                List<String> t = new ArrayList<>();
                arr.forEach(n -> t.add(n.asText()));
                talles = t;
            }
        } catch (Exception ignored) {}

        // ml_badge: comma-delimited, principal-first (design D1 badges-oportunidades-revamp) ->
        // split back into an ordered List<String>; "" (or NULL) means no badges.
        String mlBadgeRaw = rs.getString("ml_badge");
        List<String> badges = (mlBadgeRaw != null && !mlBadgeRaw.isBlank())
                ? Arrays.asList(mlBadgeRaw.split(","))
                : List.of();
        Product.MlScore ml = new Product.MlScore(
                rs.getInt("ml_score"),
                badges,
                rs.getInt("ml_oferta") == 1,
                rs.getString("ml_tendencia") != null ? rs.getString("ml_tendencia") : "",
                rs.getInt("ml_score"),
                rs.getDouble("ml_zscore"),
                rs.getString("ml_segment")   != null ? rs.getString("ml_segment")   : "standard"
        );

        String marca   = rs.getString("marca");
        String rubro   = rs.getString("rubro");
        boolean gymrat = rs.getInt("gymrat") == 1;
        boolean marcaPremium = rs.getInt("marca_premium") == 1;
        int cantidadUnidades = rs.getInt("cantidad_unidades");
        if (cantidadUnidades < 1) cantidadUnidades = 1;
        String subCategoria = rs.getString("sub_categoria");

        String fit             = rs.getString("fit");
        String estampado       = rs.getString("estampado");
        String escote          = rs.getString("escote");
        String colorDominante  = rs.getString("color_dominante");
        Product.VisualAttrs visual = new Product.VisualAttrs(
                fit            != null ? fit            : "",
                estampado      != null ? estampado      : "",
                escote         != null ? escote         : "",
                colorDominante != null ? colorDominante : "");

        return new Product(
                rs.getString("sitio"), rs.getString("nombre"),
                rs.getDouble("precio"), rs.getString("precio_orig"),
                rs.getString("url"), rs.getString("imagen_url"),
                rs.getString("categoria"), rs.getString("genero"),
                talles, ml, marca != null ? marca : "",
                rubro != null && !rubro.isBlank() ? rubro : "indumentaria",
                gymrat, marcaPremium, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, cantidadUnidades,
                subCategoria != null ? subCategoria : "", visual);
    }

    // ─── ML Output. Bodies in MlOutputRepository (backlog A3).
    // ─────────────────────────────────────────────────────────────────────

    public void guardarMlOutput(JsonNode mlOutput) {
        mlOutputRepository.guardarMlOutput(mlOutput);
    }

    public JsonNode cargarMlOutput() {
        return mlOutputRepository.cargarMlOutput();
    }

    // ─── Historial de precios (lecturas). Bodies in HistorialRepository
    // (backlog A3). Las ESCRITURAS viven en el upsert de productos.
    // ─────────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> cargarHistorial(String url) {
        return historialRepository.cargarHistorial(url);
    }

    // ─── Sitios dinámicos. Bodies in SitiosRepository (backlog A3).
    // ─────────────────────────────────────────────────────────────────────

    public void guardarSitio(String nombre, String url, String plataforma) {
        sitiosRepository.guardarSitio(nombre, url, plataforma);
    }

    public void eliminarSitio(String nombre) {
        sitiosRepository.eliminarSitio(nombre);
    }

    public List<Map<String, String>> cargarSitiosDinamicos() {
        return sitiosRepository.cargarSitiosDinamicos();
    }

    // ─── Categoria Stats. Bodies in CategoriaStatsRepository (backlog A3).
    // ─────────────────────────────────────────────────────────────────────

    public void guardarCategoriaStats(com.fasterxml.jackson.databind.JsonNode statsNode) {
        categoriaStatsRepository.guardarCategoriaStats(statsNode);
    }

    public java.util.Map<String, String> cargarCategoriaStats() {
        return categoriaStatsRepository.cargarCategoriaStats();
    }

    // ─── Precios externos. Bodies in PreciosExternosRepository (backlog A3).
    // ─────────────────────────────────────────────────────────────────────

    public void guardarPreciosExternos(String productoUrl, String sitio,
            java.util.List<java.util.Map<String,Object>> resultados) {
        preciosExternosRepository.guardarPreciosExternos(productoUrl, sitio, resultados);
    }

    public java.util.List<java.util.Map<String,Object>> cargarPreciosExternos(String productoUrl) {
        return preciosExternosRepository.cargarPreciosExternos(productoUrl);
    }

    /**
     * Actualiza la categoría de un producto (corrección por modelo ML).
     *
     * <p>Camino de MÁQUINA (design D5) — llamado en cada scrape desde
     * {@code ResultAggregator.persistirCategoriasRefinadas} y desde
     * {@code POST /api/ml/aplicar}. Lleva {@code AND bloqueado_por IS NULL}:
     * un producto bloqueado no debe perder su categoría humana-confirmada
     * por este camino. El camino HUMANO ({@link #aplicarReclasificacionAuditada},
     * vía la {@code updateNormalizacion} privada compartida) NO lleva este
     * guard — una segunda confirmación humana debe poder re-lockear un
     * producto ya bloqueado.</p>
     */
    public void actualizarCategoria(String url, String nuevaCategoria) {
        if (url == null || nuevaCategoria == null) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE productos SET categoria=? WHERE url=? AND bloqueado_por IS NULL")) {
            ps.setString(1, nuevaCategoria);
            ps.setString(2, url);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando categoria: {}", e.getMessage());
        }
    }

    /**
     * UPDATE compartido de clasificación (categoria/marca/genero/sub_categoria) +
     * talles — extraído de {@link #actualizarNormalizacion} (agent-chat-finetune
     * WU1), reutilizado por {@link #aplicarReclasificacionAuditada} (camino
     * humano, dentro de su propia transacción) y por {@link #actualizarNormalizacion}
     * (camino de máquina). {@code respectLock} decide si se agrega el guard
     * {@code AND bloqueado_por IS NULL} a la sentencia de clasificación —
     * {@code false} para el camino humano (una segunda confirmación debe poder
     * re-lockear un producto ya bloqueado), {@code true} para el de máquina.
     * Un único parámetro booleano en vez de dos sentencias mantenidas por
     * separado (review fix F1, manual-classification-lock): antes de este fix,
     * {@link #actualizarNormalizacion} traía su propio UPDATE duplicado
     * hand-rolled con el guard, y este método quedaba sin usar desde ahí pese
     * a lo que su JavaDoc afirmaba.
     *
     * <p>{@code talles} SIEMPRE se escribe en su PROPIA sentencia, sin guard:
     * no es una columna bloqueada (design D3 — {@code SpUpsertRunColumnCoverageTest}
     * la clasifica OVERWRITTEN, no LOCKED, y {@code sp_upsert_run} la sobrescribe
     * siempre sin importar el estado del lock). Bundlearla dentro del UPDATE
     * guardado congelaba {@code talles} en un producto bloqueado, contradiciendo
     * esa misma taxonomía (review fix F2). El row count devuelto es el de la
     * sentencia de CLASIFICACIÓN — la única que puede ser bloqueada — y es lo
     * que el llamador SIEMPRE debe mirar (descartar este valor era la raíz del
     * defecto "silent success" original).</p>
     */
    private int updateNormalizacion(Connection c, String url, String categoria, String marca,
                                     String genero, List<String> talles, String subCategoria,
                                     boolean respectLock) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("UPDATE productos SET talles=? WHERE url=?")) {
            ps.setString(1, MAPPER.writeValueAsString(talles != null ? talles : List.of()));
            ps.setString(2, url);
            ps.executeUpdate();
        }
        String sql = "UPDATE productos SET categoria=?, marca=?, genero=?, sub_categoria=? WHERE url=?"
                + (respectLock ? " AND bloqueado_por IS NULL" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, categoria != null ? categoria : "");
            ps.setString(2, marca != null ? marca : "");
            ps.setString(3, genero != null ? genero : "");
            ps.setString(4, subCategoria != null ? subCategoria : "");
            ps.setString(5, url);
            return ps.executeUpdate();
        }
    }

    /**
     * Actualiza categoria/marca/genero/talles de un producto ya existente en la
     * DB sin re-scrapear. Usado por la re-normalización bulk del catálogo
     * ({@code ResultAggregator#renormalizarCatalogo}): aplica las reglas
     * actuales de {@code NormalizerService} sobre datos ya persistidos.
     * Devuelve el row count real del UPDATE de clasificación (0 si la url no
     * existe, el producto está bloqueado, o hubo una excepción) — el llamador
     * lo usa para distinguir "escritura intentada" de "escritura aplicada"
     * (agent-chat-finetune WU1/WU2; antes de este fix este método era
     * {@code void} y el bulk path contaba cambios intentados como si hubieran
     * sido efectivamente persistidos).
     *
     * <p>Camino de MÁQUINA (design D5) — llamado desde el bulk path de
     * {@code ResultAggregator.renormalizarCatalogo} ({@code POST /api/ml/renormalizar}).
     * Reutiliza la {@code updateNormalizacion} privada compartida con
     * {@code respectLock=true} (review fix F1): un 0-row-count aquí sobre un
     * producto bloqueado es el comportamiento correcto, no una falla — Phase 6
     * lo distingue de una escritura fallida real. {@code talles} SIEMPRE se
     * escribe, incluso en un producto bloqueado (review fix F2) — no es una
     * columna bloqueada.</p>
     */
    public int actualizarNormalizacion(String url, String categoria, String marca,
                                        String genero, List<String> talles, String subCategoria) {
        if (url == null) return 0;
        try (Connection c = dataSource.getConnection()) {
            return updateNormalizacion(c, url, categoria, marca, genero, talles, subCategoria, true);
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando normalizacion: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Camino auditado de reclasificación humana ({@code POST /api/agent/apply},
     * agent-chat-finetune WU1 — fix del confirm-button del LLM catalog agent;
     * extendido por manual-classification-lock Phase 3 para adquirir el lock
     * de clasificación). Una sola conexión, una sola transacción: el UPDATE de
     * {@link #updateNormalizacion}, el UPDATE de {@code rubro}/lock y el
     * INSERT de auditoría se confirman juntos o ninguno de los tres. Si el
     * primer UPDATE afecta 0 filas (url inexistente) o el INSERT de auditoría
     * falla por cualquier motivo, se hace rollback completo y se devuelve
     * {@code false} — nunca queda una reclasificación sin fila de auditoría,
     * ni una fila de auditoría de algo que no pasó (tradeoff elegido: se
     * pierde un click humano confirmado antes que dejar un cambio sin
     * auditar). Los valores "antes" de la auditoría salen de {@code previo}
     * (una lectura server-side previa, nunca de valores que mande el cliente).
     *
     * <p>{@code rubro} se deriva de la {@code categoria} humana vía
     * {@link RubroResolver} (design D3) — nunca lo propone el agente — y se
     * persiste junto con el lock ({@code bloqueado_por}/{@code bloqueado_at})
     * en la MISMA transacción, así {@code sp_upsert_run} lo congela como al
     * resto de las columnas bloqueadas. {@code actor} viene de
     * {@link ar.scraper.identity.ActorResolver#current()} — nunca leído
     * inline. IMPORTANTE (Phase 4): este es un método PÚBLICO llamado por el
     * camino humano; NO lleva el guard {@code AND bloqueado_por IS NULL} —
     * una segunda confirmación humana debe poder re-lockear (con un actor
     * distinto) un producto ya bloqueado. Solo los caminos de MÁQUINA
     * ({@link #actualizarCategoria}, {@link #actualizarNormalizacion}) llevan
     * ese guard.</p>
     */
    public boolean aplicarReclasificacionAuditada(String url, String categoria, String marca,
                                                   String genero, List<String> talles, String subCategoria,
                                                   Product previo, String actor) {
        if (url == null) return false;
        String sitioKey = SiteClassification.sitioKey(previo != null ? previo.sitio() : "");
        String rubroPrevio = previo != null ? previo.rubro() : null;
        String rubro = rubroResolver.resolver(sitioKey, categoria, rubroPrevio);
        String ahora = LocalDateTime.now().format(DT);

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int rows = updateNormalizacion(c, url, categoria, marca, genero, talles, subCategoria, false);
                if (rows != 1) {
                    c.rollback();
                    return false;
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE productos SET rubro=?, bloqueado_por=?, bloqueado_at=? WHERE url=?")) {
                    ps.setString(1, rubro != null ? rubro : "indumentaria");
                    ps.setString(2, actor != null && !actor.isBlank() ? actor : "local");
                    ps.setString(3, ahora);
                    ps.setString(4, url);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO agent_reclassify_audit " +
                        "(url, categoria_antes, categoria_despues, marca_antes, marca_despues, " +
                        "genero_antes, genero_despues, sub_categoria_antes, sub_categoria_despues, " +
                        "applied_at, applied_by) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, url);
                    ps.setString(2, previo != null && previo.categoria() != null ? previo.categoria() : "");
                    ps.setString(3, categoria != null ? categoria : "");
                    ps.setString(4, previo != null && previo.marca() != null ? previo.marca() : "");
                    ps.setString(5, marca != null ? marca : "");
                    ps.setString(6, previo != null && previo.genero() != null ? previo.genero() : "");
                    ps.setString(7, genero != null ? genero : "");
                    ps.setString(8, previo != null && previo.subCategoria() != null ? previo.subCategoria() : "");
                    ps.setString(9, subCategoria != null ? subCategoria : "");
                    ps.setString(10, ahora);
                    ps.setString(11, actor != null && !actor.isBlank() ? actor : "local");
                    ps.executeUpdate();
                }
                c.commit();
                return true;
            } catch (Exception e) {
                LOG.error("[DB] Error en aplicarReclasificacionAuditada, rollback: {}", e.getMessage(), e);
                try { c.rollback(); } catch (Exception ignored) {}
                return false;
            }
        } catch (Exception e) {
            LOG.error("[DB] Error abriendo conexión en aplicarReclasificacionAuditada: {}", e.getMessage(), e);
            return false;
        }
    }


    // ─── Favoritos. Bodies in FavoritosRepository (backlog A3).
    // ─────────────────────────────────────────────────────────────────────

    public void guardarFavorito(String url, String sitio, String nombre) {
        favoritosRepository.guardarFavorito(url, sitio, nombre);
    }

    public void eliminarFavorito(String url) {
        favoritosRepository.eliminarFavorito(url);
    }

    public List<Map<String, String>> listarFavoritos() {
        return favoritosRepository.listarFavoritos();
    }

    public void tocarFavorito(String url) {
        favoritosRepository.tocarFavorito(url);
    }

    // ─── Outfit feedback + categoria dismiss. Bodies in FeedbackRepository
    // (backlog A3). The OutfitItemRow record stays HERE: callers and tests
    // name it DatabaseService.OutfitItemRow.
    // ─────────────────────────────────────────────────────────────────────

    /** Fila cruda de feedback per-item — el join con el catálogo vivo lo hace el caller. */
    public record OutfitItemRow(String slot, String url, boolean liked, String estilo) {}

    /**
     * Backward-compat overload: persiste con estilo="gym" (comportamiento previo a
     * la separación de señal por estilo). Se mantiene para callers/tests que no
     * distinguen estilo.
     */
    public void guardarOutfitFeedbackItem(String genero, String slot, String url, boolean liked) {
        guardarOutfitFeedbackItem(genero, slot, url, liked, "gym");
    }

    public void guardarOutfitFeedbackItem(String genero, String slot, String url, boolean liked, String estilo) {
        feedbackRepository.guardarOutfitFeedbackItem(genero, slot, url, liked, estilo);
    }

    public List<OutfitItemRow> obtenerOutfitFeedback() {
        return feedbackRepository.obtenerOutfitFeedback();
    }

    /**
     * Marca una categoria como "no me interesa" feed-wide. Idempotente: si la
     * categoria ya está dismissed, no inserta una fila duplicada.
     */
    public void guardarCategoriaDismiss(String categoria) {
        feedbackRepository.guardarCategoriaDismiss(categoria);
    }

    /**
     * Borra TODO el historial de feedback (todos los estilos + tabla legacy).
     * Backward-compat: el reset scoped por estilo usa {@link #limpiarOutfitFeedback(String)}.
     */
    public void limpiarOutfitFeedback() {
        feedbackRepository.limpiarOutfitFeedback();
    }

    /**
     * Borra el historial de feedback de UN estilo ("gym" | "casual"). estilo
     * null/blank → no-op.
     */
    public void limpiarOutfitFeedback(String estilo) {
        feedbackRepository.limpiarOutfitFeedback(estilo);
    }

    /** Revierte el dismiss de una categoria (undo). Safe no-op si no existía. */
    public void borrarCategoriaDismiss(String categoria) {
        feedbackRepository.borrarCategoriaDismiss(categoria);
    }

    /** Lee todas las categorias dismissed feed-wide. */
    public Set<String> obtenerCategoriaDismiss() {
        return feedbackRepository.obtenerCategoriaDismiss();
    }

    public void marcarDescontinuado(String url) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE productos SET activo=0 WHERE url=?")) {
            ps.setString(1, url);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error marcando descontinuado: {}", e.getMessage());
        }
    }

    /**
     * Live single-URL read of the classification lock (review fix F3,
     * manual-classification-lock). {@code ResultAggregator.renormalizarCatalogo}
     * snapshots {@link #cargarClasificacionBloqueada()} once at method entry
     * for the common case, but that snapshot goes stale the moment a product
     * is locked via {@code POST /api/agent/apply} mid-run — the loop makes one
     * sequential round-trip per changed product across the whole catalog, a
     * realistic window. This lets the caller attribute a 0-row guarded write
     * to "correctly skipped, now locked" from a FRESH read taken right after
     * the write attempt, instead of trusting the stale snapshot.
     */
    public boolean estaBloqueado(String url) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM productos WHERE url=? AND bloqueado_por IS NOT NULL")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error consultando bloqueo de {}: {}", url, e.getMessage());
            return false;
        }
    }

    public boolean esProductoActivo(String url) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT activo FROM productos WHERE url=?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error consultando activo: {}", e.getMessage());
            return false;
        }
    }

    public record HistorialEntry(String fecha, double precio) {}

    public List<HistorialEntry> getHistorialPrecios(String url) {
        return historialRepository.getHistorialPrecios(url);
    }

    /**
     * Variante batch de {@link #getHistorialPrecios(String)}: una sola consulta
     * {@code WHERE url IN (...)}, evitando el patrón N+1 (usado por
     * {@code SenalEnricher} sobre todo el catálogo).
     *
     * @param urls URLs de productos a consultar; URLs vacías/blank son ignoradas
     * @return mapa url -&gt; historial (orden ascendente por fecha); URLs sin
     *         historial no aparecen como key
     */
    public Map<String, List<HistorialEntry>> getHistorialPrecios(List<String> urls) {
        return historialRepository.getHistorialPrecios(urls);
    }

    // ─── Clear methods ───────────────────────────────────────────────────────

    public void limpiarProductos() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("DELETE FROM productos");
                st.execute("DELETE FROM precio_historico");
                st.execute("DELETE FROM categoria_stats");
                c.commit();
                LOG.info("[DB] Catálogo, historial y stats de categorías eliminados.");
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public void limpiarMlOutput() throws SQLException {
        mlOutputRepository.limpiarMlOutput();
    }

    // ─── Saved Outfits. Bodies in SavedOutfitsRepository (backlog A3).
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Persiste un outfit generado con su nombre, slots y suplementos en JSON, y el
     * total estimado. Retorna el id generado, o -1 en error.
     */
    public int guardarOutfit(String nombre, String slotsJson, String suplementosJson, double total) {
        return savedOutfitsRepository.guardarOutfit(nombre, slotsJson, suplementosJson, total);
    }

    /** Retorna todos los outfits guardados, ordenados por created_at DESC. */
    public List<Map<String, Object>> obtenerOutfitsGuardados() {
        return savedOutfitsRepository.obtenerOutfitsGuardados();
    }

    /** Elimina un outfit guardado por id. Retorna true si existía. */
    public boolean eliminarOutfitGuardado(int id) {
        return savedOutfitsRepository.eliminarOutfitGuardado(id);
    }

    /** Renombra un outfit guardado. Retorna true si existía. */
    public boolean renombrarOutfit(int id, String nombre) {
        return savedOutfitsRepository.renombrarOutfit(id, nombre);
    }

    // ─── Cron Jobs + Executions. Bodies in CronRepository (backlog A3);
    // this class keeps the public surface and delegates.
    // ─────────────────────────────────────────────────────────────────────

    public long insertCronJob(String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled, String nextRunAt) {
        return cronRepository.insertCronJob(name, precioMin, precioMax, sitios,
                forceRetrain, useGpu, cronExpr, enabled, nextRunAt);
    }

    /** Retorna {@code false} sin persistir si {@code id} no existe. */
    public boolean updateCronJob(long id, String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled, String nextRunAt) {
        return cronRepository.updateCronJob(id, name, precioMin, precioMax, sitios,
                forceRetrain, useGpu, cronExpr, enabled, nextRunAt);
    }

    /** Elimina el job y (cascada manual) sus ejecuciones. Retorna {@code false} si {@code id} no existía. */
    public boolean deleteCronJob(long id) {
        return cronRepository.deleteCronJob(id);
    }

    public List<CronJob> listCronJobs() {
        return cronRepository.listCronJobs();
    }

    public Optional<CronJob> getCronJob(long id) {
        return cronRepository.getCronJob(id);
    }

    /** Actualiza SOLO {@code last_run_at} — usado por {@code CronJobRunner} al disparar/skippear un run. */
    public boolean touchLastRunAt(long jobId, String lastRunAt) {
        return cronRepository.touchLastRunAt(jobId, lastRunAt);
    }

    /** Actualiza SOLO {@code next_run_at} — usado por {@code CronSchedulerService} tras cada poll. */
    public boolean updateNextRunAt(long jobId, String nextRunAt) {
        return cronRepository.updateNextRunAt(jobId, nextRunAt);
    }

    public long insertCronExecution(long jobId, String startedAt, String status, String skippedReason) {
        return cronRepository.insertCronExecution(jobId, startedAt, status, skippedReason);
    }

    public boolean updateCronExecution(long execId, String finishedAt, String status,
            String skippedReason, String logOutput, Integer durationMs) {
        return cronRepository.updateCronExecution(execId, finishedAt, status,
                skippedReason, logOutput, durationMs);
    }

    public List<CronExecution> listExecutions(long jobId, int limit) {
        return cronRepository.listExecutions(jobId, limit);
    }

    public Optional<CronExecution> getExecution(long execId) {
        return cronRepository.getExecution(execId);
    }

    /** Retiene solo las últimas {@code keep} ejecuciones por job (decision 7: 50). */
    public void pruneCronExecutions(long jobId, int keep) {
        cronRepository.pruneCronExecutions(jobId, keep);
    }

    // ─── Stats ───────────────────────────────────────────────────────────────

    public record UpsertStats(int nuevos, int actualizados, int sinCambios, int desactivados) {}
}
