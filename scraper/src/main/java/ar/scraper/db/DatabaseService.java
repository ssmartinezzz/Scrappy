package ar.scraper.db;

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

    public DatabaseService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void init() {
        try {
            seedPresetIlustrativoSiVacio();
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

    // ─── Presets de financiación ─────────────────────────────────────────────

    private static final String PRESET_ILUSTRATIVO_LABEL =
            "Ejemplo — 12 cuotas / 40% recargo (editá este valor)";
    private static final double PRESET_ILUSTRATIVO_RECARGO_PCT = 40.0;
    private static final int    PRESET_ILUSTRATIVO_CUOTAS      = 12;

    public record Preset(int id, String label, double recargoPct, int cuotas, boolean activo) {}

    /**
     * En el primer arranque (tabla vacía), crea un preset ilustrativo marcado
     * explícitamente como ejemplo y lo deja activo, para que la señal de
     * financiación tenga un valor de referencia desde el día uno sin requerir
     * que el usuario configure nada manualmente.
     */
    private void seedPresetIlustrativoSiVacio() throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM financiacion_presets")) {
            if (rs.next() && rs.getInt(1) == 0) {
                crearPresetInterno(c, PRESET_ILUSTRATIVO_LABEL, PRESET_ILUSTRATIVO_RECARGO_PCT,
                        PRESET_ILUSTRATIVO_CUOTAS, true);
                LOG.info("[DB] Preset ilustrativo creado y activado (tabla vacía).");
            }
        }
    }

    private int crearPresetInterno(Connection c, String label, double recargoPct, int cuotas, boolean activo)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO financiacion_presets (label, recargo_pct, cuotas, activo, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, label);
            ps.setDouble(2, recargoPct);
            ps.setInt(3, cuotas);
            ps.setInt(4, activo ? 1 : 0);
            ps.setString(5, LocalDateTime.now().format(DT));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        }
    }

    public List<Preset> listarPresets() {
        List<Preset> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT id, label, recargo_pct, cuotas, activo FROM financiacion_presets ORDER BY created_at, id")) {
            while (rs.next()) {
                result.add(new Preset(
                        rs.getInt("id"), rs.getString("label"),
                        rs.getDouble("recargo_pct"), rs.getInt("cuotas"),
                        rs.getInt("activo") == 1));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error listando presets: {}", e.getMessage());
        }
        return result;
    }

    public Optional<Preset> cargarPresetActivo() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT id, label, recargo_pct, cuotas, activo FROM financiacion_presets WHERE activo=1 LIMIT 1")) {
            if (rs.next()) {
                return Optional.of(new Preset(
                        rs.getInt("id"), rs.getString("label"),
                        rs.getDouble("recargo_pct"), rs.getInt("cuotas"), true));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando preset activo: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Crea un preset nuevo, inactivo por defecto. Retorna el id generado, o -1 en
     * error o si {@code cuotas}/{@code recargoPct} son inválidos (mismo criterio
     * que {@code FinanciacionCalculator.compute}: cuotas&gt;0 y recargoPct&gt;-100).
     */
    public int crearPreset(String label, double recargoPct, int cuotas) {
        if (cuotas <= 0 || recargoPct <= -100) {
            LOG.warn("[DB] crearPreset rechazado: cuotas={} recargoPct={} inválidos", cuotas, recargoPct);
            return -1;
        }
        try (Connection c = dataSource.getConnection()) {
            return crearPresetInterno(c, label, recargoPct, cuotas, false);
        } catch (Exception e) {
            LOG.warn("[DB] Error creando preset: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Edita label/recargoPct/cuotas de un preset existente. No altera su estado activo.
     * Retorna {@code false} sin persistir si {@code cuotas}/{@code recargoPct} son
     * inválidos (mismo criterio que {@code FinanciacionCalculator.compute}: cuotas&gt;0
     * y recargoPct&gt;-100), o si ocurre un error.
     */
    public boolean editarPreset(int id, String label, double recargoPct, int cuotas) {
        if (cuotas <= 0 || recargoPct <= -100) {
            LOG.warn("[DB] editarPreset rechazado: cuotas={} recargoPct={} inválidos", cuotas, recargoPct);
            return false;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    UPDATE financiacion_presets SET label=?, recargo_pct=?, cuotas=? WHERE id=?
                    """)) {
            ps.setString(1, label);
            ps.setDouble(2, recargoPct);
            ps.setInt(3, cuotas);
            ps.setInt(4, id);
            int filasEditadas = ps.executeUpdate();
            if (filasEditadas == 0) {
                LOG.warn("[DB] editarPreset: id {} no existe.", id);
                return false;
            }
            return true;
        } catch (Exception e) {
            LOG.warn("[DB] Error editando preset {}: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * Activa el preset {@code id} y desactiva todos los demás, de forma transaccional.
     * Retorna {@code false} (y revierte la desactivación) si {@code id} no existe —
     * evita quedar sin ningún preset activo por un id inválido/obsoleto.
     */
    public boolean activarPreset(int id) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement psOff = c.prepareStatement(
                    "UPDATE financiacion_presets SET activo=0 WHERE activo=1");
                 PreparedStatement psOn = c.prepareStatement(
                    "UPDATE financiacion_presets SET activo=1 WHERE id=?")) {
                psOff.executeUpdate();
                psOn.setInt(1, id);
                int filasActivadas = psOn.executeUpdate();
                if (filasActivadas == 0) {
                    LOG.warn("[DB] activarPreset: id {} no existe, se revierte desactivación.", id);
                    c.rollback();
                    return false;
                }
                c.commit();
                return true;
            } catch (Exception e) {
                LOG.warn("[DB] Error activando preset {}: {}", id, e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
                return false;
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error activando preset {}: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * Elimina un preset. Comportamiento resuelto en el diseño para el caso
     * "borrar el preset activo":
     * <ul>
     *   <li>Si es el ÚNICO preset restante (activo o no) → se borra y se
     *       recrea el preset ilustrativo por defecto, activo (evita un estado
     *       de tabla vacía sin recuperación automática).</li>
     *   <li>Si quedan OTROS presets → se borra y NINGUNO se auto-activa; el
     *       catálogo cae a {@code sin_preset_activo} hasta que el usuario
     *       active uno explícitamente.</li>
     * </ul>
     *
     * @return {@code true} si el {@code id} pedido efectivamente existía y fue
     *         borrado; {@code false} si no existía (no-op) o si ocurrió un error.
     */
    public boolean eliminarPreset(int id) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                int total;
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM financiacion_presets")) {
                    total = rs.next() ? rs.getInt(1) : 0;
                }

                int filasBorradas;
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM financiacion_presets WHERE id=?")) {
                    ps.setInt(1, id);
                    filasBorradas = ps.executeUpdate();
                }

                if (filasBorradas > 0 && (total - filasBorradas) <= 0) {
                    crearPresetInterno(c, PRESET_ILUSTRATIVO_LABEL, PRESET_ILUSTRATIVO_RECARGO_PCT,
                            PRESET_ILUSTRATIVO_CUOTAS, true);
                    LOG.info("[DB] Último preset eliminado: preset ilustrativo recreado y activado.");
                }

                c.commit();
                return filasBorradas > 0;
            } catch (Exception e) {
                LOG.warn("[DB] Error eliminando preset {}: {}", id, e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
                return false;
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error eliminando preset {}: {}", id, e.getMessage());
            return false;
        }
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

    // ─── ML Output ──────────────────────────────────────────────────────────

    public void guardarMlOutput(JsonNode mlOutput) {
        if (mlOutput == null) return;
        if (!esMlOutputValido(mlOutput)) {
            LOG.debug("[DB] ML output inválido (sin scores/tendencias) — no se persiste");
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                String json = MAPPER.writeValueAsString(mlOutput);
                String now  = LocalDateTime.now().format(DT);
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO ml_output (payload, created_at) VALUES (?, ?)")) {
                    ps.setString(1, json);
                    ps.setString(2, now);
                    ps.executeUpdate();
                }
                // Mantener solo los últimos 10 outputs
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("""
                        DELETE FROM ml_output WHERE id NOT IN (
                            SELECT id FROM ml_output ORDER BY id DESC LIMIT 10
                        )""");
                }
                c.commit();
            } catch (Exception e) {
                LOG.warn("[DB] Error guardando ML output: {}", e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error guardando ML output: {}", e.getMessage());
        }
    }

    /**
     * VALID == tiene un nodo {@code scores} objeto no vacío Y un nodo {@code tendencias}
     * presente como objeto. Mismo criterio usado por {@code ApiController.tendencias()}
     * (R1/R3) — garantiza que todo lo que se persiste, se puede servir.
     */
    private boolean esMlOutputValido(JsonNode ml) {
        if (ml == null || ml.isNull() || !ml.isObject()) return false;
        JsonNode scores = ml.path("scores");
        if (!scores.isObject() || scores.isEmpty()) return false;
        JsonNode tend = ml.path("tendencias");
        return tend.isObject();
    }

    public JsonNode cargarMlOutput() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT payload FROM ml_output ORDER BY id DESC LIMIT 1")) {
            if (rs.next()) {
                return MAPPER.readTree(rs.getString(1));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando ML output: {}", e.getMessage());
        }
        return null;
    }

    // ─── Historial de precios ────────────────────────────────────────────────

    public List<Map<String, Object>> cargarHistorial(String url) {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT fecha, precio FROM precio_historico WHERE url=? ORDER BY fecha ASC")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("fecha",  rs.getString(1));
                    row.put("precio", rs.getDouble(2));
                    result.add(row);
                }
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error historial: {}", e.getMessage());
        }
        return result;
    }

    // ─── Sitios dinámicos ────────────────────────────────────────────────────

    public void guardarSitio(String nombre, String url, String plataforma) {
        Objects.requireNonNull(nombre, "nombre must not be null");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO sitios_dinamicos (nombre, url, plataforma, created_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(nombre) DO UPDATE SET url=excluded.url, plataforma=excluded.plataforma
                    """)) {
            ps.setString(1, nombre);
            ps.setString(2, url);
            ps.setString(3, plataforma);
            ps.setString(4, LocalDateTime.now().format(DT));
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando sitio: {}", e.getMessage());
        }
    }

    public void eliminarSitio(String nombre) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM sitios_dinamicos WHERE nombre=?")) {
            ps.setString(1, nombre);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando sitio: {}", e.getMessage());
        }
    }

    public List<Map<String, String>> cargarSitiosDinamicos() {
        List<Map<String, String>> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT nombre, url, plataforma FROM sitios_dinamicos ORDER BY created_at")) {
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("nombre",     rs.getString(1));
                row.put("url",        rs.getString(2));
                row.put("plataforma", rs.getString(3));
                result.add(row);
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando sitios: {}", e.getMessage());
        }
        return result;
    }

    // ─── Categoria Stats ─────────────────────────────────────────────────────────

    public void guardarCategoriaStats(com.fasterxml.jackson.databind.JsonNode statsNode) {
        if (statsNode == null) return;
        String now = LocalDateTime.now().format(DT);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                var it = statsNode.fields();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO categoria_stats (categoria, payload, updated_at) VALUES (?,?,?) " +
                        "ON CONFLICT(categoria) DO UPDATE SET payload=excluded.payload, updated_at=excluded.updated_at")) {
                    while (it.hasNext()) {
                        var entry = it.next();
                        ps.setString(1, entry.getKey());
                        ps.setString(2, entry.getValue().toString());
                        ps.setString(3, now);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (Exception e) {
                LOG.warn("[DB] Error guardando categoria_stats: {}", e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error guardando categoria_stats: {}", e.getMessage());
        }
    }

    public java.util.Map<String, String> cargarCategoriaStats() {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT categoria, payload FROM categoria_stats ORDER BY categoria")) {
            while (rs.next()) result.put(rs.getString(1), rs.getString(2));
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando categoria_stats: {}", e.getMessage());
        }
        return result;
    }

    // ─── Precios externos (MercadoLibre, etc.) ───────────────────────────────────

    public void guardarPreciosExternos(String productoUrl, String sitio,
            java.util.List<java.util.Map<String,Object>> resultados) {
        if (resultados == null || resultados.isEmpty()) return;
        String hoy = LocalDate.now().format(DATE);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Borrar los del día para no duplicar
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM precios_externos WHERE producto_url=? AND sitio=? AND fecha=?")) {
                    del.setString(1, productoUrl); del.setString(2, sitio); del.setString(3, hoy);
                    del.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO precios_externos (producto_url,sitio,titulo,precio,externo_url,condicion,fecha) VALUES(?,?,?,?,?,?,?)")) {
                    for (var r : resultados) {
                        ps.setString(1, productoUrl);
                        ps.setString(2, sitio);
                        ps.setString(3, (String) r.getOrDefault("titulo", ""));
                        ps.setDouble(4, ((Number) r.getOrDefault("precio", 0.0)).doubleValue());
                        ps.setString(5, (String) r.getOrDefault("url", ""));
                        ps.setString(6, (String) r.getOrDefault("condicion", "new"));
                        ps.setString(7, hoy);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (Exception e) {
                LOG.warn("[DB] Error guardando precios_externos: {}", e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error guardando precios_externos: {}", e.getMessage());
        }
    }

    public java.util.List<java.util.Map<String,Object>> cargarPreciosExternos(String productoUrl) {
        var result = new java.util.ArrayList<java.util.Map<String,Object>>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT sitio,titulo,precio,externo_url,condicion,fecha " +
                "FROM precios_externos WHERE producto_url=? ORDER BY fecha DESC, precio ASC LIMIT 20")) {
            ps.setString(1, productoUrl);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    var row = new java.util.LinkedHashMap<String,Object>();
                    row.put("sitio",     rs.getString("sitio"));
                    row.put("titulo",    rs.getString("titulo"));
                    row.put("precio",    rs.getDouble("precio"));
                    row.put("url",       rs.getString("externo_url"));
                    row.put("condicion", rs.getString("condicion"));
                    row.put("fecha",     rs.getString("fecha"));
                    result.add(row);
                }
            }
        } catch (Exception e) { LOG.warn("[DB] Error cargando precios_externos: {}", e.getMessage()); }
        return result;
    }

    /** Actualiza la categoría de un producto (corrección por modelo ML) */
    public void actualizarCategoria(String url, String nuevaCategoria) {
        if (url == null || nuevaCategoria == null) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE productos SET categoria=? WHERE url=?")) {
            ps.setString(1, nuevaCategoria);
            ps.setString(2, url);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando categoria: {}", e.getMessage());
        }
    }

    /**
     * Actualiza categoria/marca/genero/talles de un producto ya existente en la
     * DB sin re-scrapear. Usado por la re-normalización del catálogo: aplica las
     * reglas actuales de {@code NormalizerService} sobre datos ya persistidos.
     */
    public void actualizarNormalizacion(String url, String categoria, String marca,
                                         String genero, List<String> talles, String subCategoria) {
        if (url == null) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE productos SET categoria=?, marca=?, genero=?, talles=?, sub_categoria=? WHERE url=?")) {
            ps.setString(1, categoria != null ? categoria : "");
            ps.setString(2, marca != null ? marca : "");
            ps.setString(3, genero != null ? genero : "");
            ps.setString(4, MAPPER.writeValueAsString(talles != null ? talles : List.of()));
            ps.setString(5, subCategoria != null ? subCategoria : "");
            ps.setString(6, url);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando normalizacion: {}", e.getMessage());
        }
    }


    // ─── Favoritos ───────────────────────────────────────────────────────────

    public void guardarFavorito(String url, String sitio, String nombre) {
        Objects.requireNonNull(url, "url must not be null");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO favoritos (url, sitio, nombre, added_at, last_checked_at)
                    VALUES (?, ?, ?, ?, NULL)
                    ON CONFLICT(url) DO UPDATE SET sitio=excluded.sitio, nombre=excluded.nombre
                    """)) {
            ps.setString(1, url);
            ps.setString(2, sitio);
            ps.setString(3, nombre);
            ps.setString(4, LocalDateTime.now().format(DT));
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando favorito: {}", e.getMessage());
        }
    }

    public void eliminarFavorito(String url) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM favoritos WHERE url=?")) {
            ps.setString(1, url);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando favorito: {}", e.getMessage());
        }
    }

    public List<Map<String, String>> listarFavoritos() {
        List<Map<String, String>> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT url, sitio, nombre, added_at, last_checked_at " +
                "FROM favoritos ORDER BY added_at DESC")) {
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("url",            rs.getString(1));
                row.put("sitio",          rs.getString(2));
                row.put("nombre",         rs.getString(3));
                row.put("added_at",       rs.getString(4));
                row.put("last_checked_at", rs.getString(5));
                result.add(row);
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error listando favoritos: {}", e.getMessage());
        }
        return result;
    }

    // ─── Outfit feedback ─────────────────────────────────────────────────────

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

    /**
     * Persiste un único veredicto (slot, url, liked, estilo) en outfit_feedback_item —
     * una fila por item calificado (ADR-1 de outfit-per-item-feedback). El estilo
     * ("gym" | "casual" | "catalog") separa la señal de gusto por superficie: el
     * builder gym y casual leen buckets distintos, el feed usa "catalog" (ver
     * ApiController.buildFeedbackModel).
     */
    public void guardarOutfitFeedbackItem(String genero, String slot, String url, boolean liked, String estilo) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO outfit_feedback_item
                        (genero, slot, url, liked, estilo, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
            ps.setString(1, genero);
            ps.setString(2, slot);
            ps.setString(3, url);
            ps.setInt(4, liked ? 1 : 0);
            ps.setString(5, (estilo == null || estilo.isBlank()) ? "gym" : estilo);
            ps.setString(6, LocalDateTime.now().format(DT));
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando outfit feedback item: {}", e.getMessage());
        }
    }

    /**
     * Lee todas las filas de outfit_feedback_item (con su estilo). Sin filtro por
     * genero ni estilo — el filtrado por estilo lo hace el caller
     * (ApiController.buildFeedbackModel) según la superficie. El caller también hace
     * el join url→Product contra el catálogo vivo, ya que esta clase no conoce el
     * AggregatedResult en memoria.
     */
    public List<OutfitItemRow> obtenerOutfitFeedback() {
        List<OutfitItemRow> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT slot, url, liked, estilo FROM outfit_feedback_item")) {
            while (rs.next()) {
                String estilo = rs.getString("estilo");
                result.add(new OutfitItemRow(
                        rs.getString("slot"),
                        rs.getString("url"),
                        rs.getInt("liked") == 1,
                        (estilo == null || estilo.isBlank()) ? "gym" : estilo));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando outfit feedback item: {}", e.getMessage());
        }
        return result;
    }

    // ─── Categoria dismiss (feed de recomendados) ────────────────────────────

    /**
     * Marca una categoria como "no me interesa" feed-wide (Decision 1 de
     * design.md, personalized-recommendations-feed). Idempotente: si la
     * categoria ya está dismissed, no inserta una fila duplicada.
     */
    public void guardarCategoriaDismiss(String categoria) {
        if (categoria == null || categoria.isBlank()) return;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement check = c.prepareStatement(
                        "SELECT 1 FROM categoria_dismiss WHERE categoria=?")) {
                    check.setString(1, categoria);
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) {
                            c.rollback(); // ya existe — no-op idempotente
                            return;
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO categoria_dismiss (categoria, created_at)
                        VALUES (?, ?)
                        """)) {
                    ps.setString(1, categoria);
                    ps.setString(2, LocalDateTime.now().format(DT));
                    ps.executeUpdate();
                    c.commit();
                }
            } catch (Exception e) {
                LOG.warn("[DB] Error guardando categoria dismiss: {}", e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error guardando categoria dismiss: {}", e.getMessage());
        }
    }

    /**
     * Borra TODO el historial de feedback (todos los estilos + tabla legacy).
     * Backward-compat: el reset scoped por estilo usa {@link #limpiarOutfitFeedback(String)}.
     */
    public void limpiarOutfitFeedback() {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM outfit_feedback_item");
                st.executeUpdate("DELETE FROM outfit_feedback");
                c.commit();
            } catch (Exception e) {
                LOG.warn("[DB] Error limpiando outfit feedback: {}", e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error limpiando outfit feedback: {}", e.getMessage());
        }
    }

    /**
     * Borra el historial de feedback de UN estilo ("gym" | "casual"). No toca las
     * filas de otros estilos ni las del feed ("catalog") — el reset de gustos de
     * cada superficie del builder es independiente. estilo null/blank → no-op
     * (evita borrar todo por accidente; para eso está el overload sin argumentos).
     */
    public void limpiarOutfitFeedback(String estilo) {
        if (estilo == null || estilo.isBlank()) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM outfit_feedback_item WHERE estilo=?")) {
            ps.setString(1, estilo);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error limpiando outfit feedback (estilo={}): {}", estilo, e.getMessage());
        }
    }

    /** Revierte el dismiss de una categoria (undo). Safe no-op si no existía. */
    public void borrarCategoriaDismiss(String categoria) {
        if (categoria == null || categoria.isBlank()) return;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM categoria_dismiss WHERE categoria=?")) {
            ps.setString(1, categoria);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error borrando categoria dismiss: {}", e.getMessage());
        }
    }

    /** Lee todas las categorias dismissed feed-wide. */
    public Set<String> obtenerCategoriaDismiss() {
        Set<String> result = new HashSet<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT categoria FROM categoria_dismiss")) {
            while (rs.next()) {
                result.add(rs.getString("categoria"));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando categoria dismiss: {}", e.getMessage());
        }
        return result;
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

    public void tocarFavorito(String url) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE favoritos SET last_checked_at=? WHERE url=?")) {
            ps.setString(1, LocalDateTime.now().format(DT));
            ps.setString(2, url);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando last_checked_at: {}", e.getMessage());
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
        var result = new java.util.ArrayList<HistorialEntry>();
        if (url == null) return result;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT fecha, precio FROM precio_historico WHERE url=? ORDER BY fecha")) {
            ps.setString(1, url);
            var rs = ps.executeQuery();
            while (rs.next())
                result.add(new HistorialEntry(rs.getString("fecha"), rs.getDouble("precio")));
        } catch (Exception e) {
            LOG.warn("[DB] historial {}: {}", url, e.getMessage());
        }
        return result;
    }

    /**
     * Variante batch de {@link #getHistorialPrecios(String)}: carga el historial de
     * múltiples URLs en una sola consulta {@code WHERE url IN (...)}, evitando el
     * patrón N+1 que resultaría de llamar la versión single-URL por producto
     * (usado por {@code SenalEnricher} para precomputar señal de compra sobre todo
     * el catálogo en un solo round-trip a la DB).
     *
     * @param urls URLs de productos a consultar; URLs vacías/blank son ignoradas
     * @return mapa url -&gt; historial (orden ascendente por fecha); URLs sin
     *         historial no aparecen como key
     */
    public Map<String, List<HistorialEntry>> getHistorialPrecios(List<String> urls) {
        Map<String, List<HistorialEntry>> result = new HashMap<>();
        if (urls == null || urls.isEmpty()) return result;

        List<String> validUrls = urls.stream()
                .filter(u -> u != null && !u.isBlank())
                .distinct()
                .toList();
        if (validUrls.isEmpty()) return result;

        String placeholders = String.join(",", validUrls.stream().map(u -> "?").toList());
        String sql = "SELECT url, fecha, precio FROM precio_historico WHERE url IN (" +
                placeholders + ") ORDER BY url, fecha";

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < validUrls.size(); i++) {
                ps.setString(i + 1, validUrls.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String url = rs.getString("url");
                    result.computeIfAbsent(url, k -> new ArrayList<>())
                          .add(new HistorialEntry(rs.getString("fecha"), rs.getDouble("precio")));
                }
            }
        } catch (Exception e) {
            LOG.warn("[DB] historial batch ({} urls): {}", validUrls.size(), e.getMessage());
        }
        return result;
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
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("DELETE FROM ml_output");
                c.commit();
                LOG.info("[DB] Datos ML eliminados.");
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    // ─── Saved Outfits ────────────────────────────────────────────────────────────

    /**
     * Persiste un outfit generado con su nombre, slots y suplementos en JSON, y el
     * total estimado. Retorna el id generado, o -1 en error.
     */
    public int guardarOutfit(String nombre, String slotsJson, String suplementosJson, double total) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO saved_outfits (nombre, slots_json, suplementos_json, total_estimado, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, nombre != null ? nombre : "Outfit");
            ps.setString(2, slotsJson != null ? slotsJson : "[]");
            ps.setString(3, suplementosJson);
            ps.setDouble(4, total);
            ps.setString(5, LocalDateTime.now().format(DT));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getInt(1) : -1;
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando outfit: {}", e.getMessage());
            return -1;
        }
    }

    /** Retorna todos los outfits guardados, ordenados por created_at DESC. */
    public List<Map<String, Object>> obtenerOutfitsGuardados() {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             java.sql.Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT id, nombre, slots_json, suplementos_json, total_estimado, created_at " +
                "FROM saved_outfits ORDER BY created_at DESC")) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id",            rs.getInt("id"));
                row.put("nombre",        rs.getString("nombre"));
                row.put("totalEstimado", rs.getDouble("total_estimado"));
                row.put("createdAt",     rs.getString("created_at"));
                String slotsJson = rs.getString("slots_json");
                String suplJson  = rs.getString("suplementos_json");
                try { row.put("slots",       MAPPER.readValue(slotsJson, List.class)); }
                catch (Exception e) { row.put("slots", List.of()); }
                try { row.put("suplementos", suplJson != null ? MAPPER.readValue(suplJson, List.class) : List.of()); }
                catch (Exception e) { row.put("suplementos", List.of()); }
                result.add(row);
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error obteniendo outfits guardados: {}", e.getMessage());
        }
        return result;
    }

    /** Elimina un outfit guardado por id. Retorna true si existía. */
    public boolean eliminarOutfitGuardado(int id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM saved_outfits WHERE id=?")) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando outfit guardado {}: {}", id, e.getMessage());
            return false;
        }
    }

    /** Renombra un outfit guardado. Retorna true si existía. */
    public boolean renombrarOutfit(int id, String nombre) {
        if (nombre == null || nombre.isBlank()) return false;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE saved_outfits SET nombre=? WHERE id=?")) {
            ps.setString(1, nombre.trim());
            ps.setInt(2, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error renombrando outfit {}: {}", id, e.getMessage());
            return false;
        }
    }

    // ─── Cron Jobs ───────────────────────────────────────────────────────────
    // Ya no hay writeLock global: cada método toma su propia conexión pooled;
    // la correctitud concurrente la da Postgres MVCC (design D1), no un lock
    // de aplicación.

    public long insertCronJob(String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled, String nextRunAt) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO cron_jobs
                        (name, precio_min, precio_max, sitios_json, force_retrain, use_gpu,
                         cron_expr, enabled, created_at, updated_at, next_run_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
            String now = LocalDateTime.now().format(DT);
            ps.setString(1, name);
            ps.setDouble(2, precioMin);
            ps.setDouble(3, precioMax);
            ps.setString(4, MAPPER.writeValueAsString(sitios != null ? sitios : List.of()));
            ps.setInt(5, forceRetrain ? 1 : 0);
            ps.setInt(6, useGpu ? 1 : 0);
            ps.setString(7, cronExpr);
            ps.setInt(8, enabled ? 1 : 0);
            ps.setString(9, now);
            ps.setString(10, now);
            ps.setString(11, nextRunAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error creando cron job: {}", e.getMessage());
            return -1;
        }
    }

    /** Retorna {@code false} sin persistir si {@code id} no existe. */
    public boolean updateCronJob(long id, String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled, String nextRunAt) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    UPDATE cron_jobs SET name=?, precio_min=?, precio_max=?, sitios_json=?,
                        force_retrain=?, use_gpu=?, cron_expr=?, enabled=?, updated_at=?, next_run_at=?
                    WHERE id=?
                    """)) {
            ps.setString(1, name);
            ps.setDouble(2, precioMin);
            ps.setDouble(3, precioMax);
            ps.setString(4, MAPPER.writeValueAsString(sitios != null ? sitios : List.of()));
            ps.setInt(5, forceRetrain ? 1 : 0);
            ps.setInt(6, useGpu ? 1 : 0);
            ps.setString(7, cronExpr);
            ps.setInt(8, enabled ? 1 : 0);
            ps.setString(9, LocalDateTime.now().format(DT));
            ps.setString(10, nextRunAt);
            ps.setLong(11, id);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando cron job {}: {}", id, e.getMessage());
            return false;
        }
    }

    /** Elimina el job y (cascada manual) sus ejecuciones. Retorna {@code false} si {@code id} no existía. */
    public boolean deleteCronJob(long id) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement delExec = c.prepareStatement("DELETE FROM cron_executions WHERE job_id=?");
                 PreparedStatement delJob  = c.prepareStatement("DELETE FROM cron_jobs WHERE id=?")) {
                delExec.setLong(1, id);
                delExec.executeUpdate();
                delJob.setLong(1, id);
                int rows = delJob.executeUpdate();
                if (rows == 0) { c.rollback(); return false; }
                c.commit();
                return true;
            } catch (Exception e) {
                LOG.warn("[DB] Error eliminando cron job {}: {}", id, e.getMessage());
                try { c.rollback(); } catch (Exception ignored) {}
                return false;
            }
        } catch (SQLException e) {
            LOG.warn("[DB] Error eliminando cron job {}: {}", id, e.getMessage());
            return false;
        }
    }

    public List<CronJob> listCronJobs() {
        List<CronJob> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                "SELECT id,name,precio_min,precio_max,sitios_json,force_retrain,use_gpu,cron_expr," +
                "enabled,created_at,updated_at,last_run_at,next_run_at FROM cron_jobs ORDER BY id")) {
            while (rs.next()) result.add(cronJobDesdeFila(rs));
        } catch (Exception e) {
            LOG.warn("[DB] Error listando cron jobs: {}", e.getMessage());
        }
        return result;
    }

    public Optional<CronJob> getCronJob(long id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT id,name,precio_min,precio_max,sitios_json,force_retrain,use_gpu,cron_expr," +
                "enabled,created_at,updated_at,last_run_at,next_run_at FROM cron_jobs WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(cronJobDesdeFila(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error obteniendo cron job {}: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    private CronJob cronJobDesdeFila(ResultSet rs) throws SQLException {
        List<String> sitios = List.of();
        try {
            JsonNode arr = MAPPER.readTree(rs.getString("sitios_json"));
            if (arr.isArray()) {
                List<String> s = new ArrayList<>();
                arr.forEach(n -> s.add(n.asText()));
                sitios = s;
            }
        } catch (Exception ignored) {}
        return new CronJob(
                rs.getLong("id"), rs.getString("name"),
                rs.getDouble("precio_min"), rs.getDouble("precio_max"), sitios,
                rs.getInt("force_retrain") == 1, rs.getInt("use_gpu") == 1,
                rs.getString("cron_expr"), rs.getInt("enabled") == 1,
                rs.getString("created_at"), rs.getString("updated_at"),
                rs.getString("last_run_at"), rs.getString("next_run_at"));
    }

    /** Actualiza SOLO {@code last_run_at} — usado por {@code CronJobRunner} al disparar/skippear un run. */
    public boolean touchLastRunAt(long jobId, String lastRunAt) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE cron_jobs SET last_run_at=? WHERE id=?")) {
            ps.setString(1, lastRunAt);
            ps.setLong(2, jobId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando last_run_at job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    /** Actualiza SOLO {@code next_run_at} — usado por {@code CronSchedulerService} tras cada poll. */
    public boolean updateNextRunAt(long jobId, String nextRunAt) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE cron_jobs SET next_run_at=? WHERE id=?")) {
            ps.setString(1, nextRunAt);
            ps.setLong(2, jobId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando next_run_at job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    // ─── Cron Executions ────────────────────────────────────────────────────

    public long insertCronExecution(long jobId, String startedAt, String status, String skippedReason) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO cron_executions (job_id, started_at, status, skipped_reason)
                    VALUES (?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, jobId);
            ps.setString(2, startedAt);
            ps.setString(3, status);
            ps.setString(4, skippedReason);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error creando cron execution (job {}): {}", jobId, e.getMessage());
            return -1;
        }
    }

    public boolean updateCronExecution(long execId, String finishedAt, String status,
            String skippedReason, String logOutput, Integer durationMs) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    UPDATE cron_executions
                    SET finished_at=?, status=?, skipped_reason=?, log_output=?, duration_ms=?
                    WHERE id=?
                    """)) {
            ps.setString(1, finishedAt);
            ps.setString(2, status);
            ps.setString(3, skippedReason);
            ps.setString(4, logOutput);
            if (durationMs != null) ps.setInt(5, durationMs); else ps.setNull(5, Types.INTEGER);
            ps.setLong(6, execId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando cron execution {}: {}", execId, e.getMessage());
            return false;
        }
    }

    public List<CronExecution> listExecutions(long jobId, int limit) {
        List<CronExecution> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT id,job_id,started_at,finished_at,status,skipped_reason,log_output,duration_ms " +
                "FROM cron_executions WHERE job_id=? ORDER BY id DESC LIMIT ?")) {
            ps.setLong(1, jobId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(cronExecutionDesdeFila(rs));
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error listando cron executions (job {}): {}", jobId, e.getMessage());
        }
        return result;
    }

    public Optional<CronExecution> getExecution(long execId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT id,job_id,started_at,finished_at,status,skipped_reason,log_output,duration_ms " +
                "FROM cron_executions WHERE id=?")) {
            ps.setLong(1, execId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(cronExecutionDesdeFila(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error obteniendo cron execution {}: {}", execId, e.getMessage());
            return Optional.empty();
        }
    }

    private CronExecution cronExecutionDesdeFila(ResultSet rs) throws SQLException {
        int durMs = rs.getInt("duration_ms");
        Integer duration = rs.wasNull() ? null : durMs;
        return new CronExecution(
                rs.getLong("id"), rs.getLong("job_id"),
                rs.getString("started_at"), rs.getString("finished_at"),
                rs.getString("status"), rs.getString("skipped_reason"),
                rs.getString("log_output"), duration);
    }

    /** Retiene solo las últimas {@code keep} ejecuciones por job (decision 7: 50). */
    public void pruneCronExecutions(long jobId, int keep) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    DELETE FROM cron_executions WHERE job_id=? AND id NOT IN (
                        SELECT id FROM cron_executions WHERE job_id=? ORDER BY id DESC LIMIT ?
                    )
                    """)) {
            ps.setLong(1, jobId);
            ps.setLong(2, jobId);
            ps.setInt(3, keep);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error pruning cron executions (job {}): {}", jobId, e.getMessage());
        }
    }

    // ─── Stats ───────────────────────────────────────────────────────────────

    public record UpsertStats(int nuevos, int actualizados, int sinCambios, int desactivados) {}
}
