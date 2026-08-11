package ar.scraper.db;

import ar.scraper.aggregator.normalize.RubroResolver;
import ar.scraper.aggregator.normalize.SiteClassification;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Persistence for the product aggregate: the scrape write-path
 * ({@code sp_upsert_run} + soft-delete + history pruning), catalog reads, the
 * machine and human classification paths, and the destructive catalog clear.
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3). It also owns
 * the WRITES to {@code precio_historico} — they happen inside the upsert
 * function and the pruning, never through a method of their own, which is why
 * {@link HistorialRepository} holds only reads.</p>
 *
 * <p>{@code UpsertStats} stays nested on DatabaseService — callers and tests
 * name it {@code DatabaseService.UpsertStats}.</p>
 */
class ProductRepository {

    private static final Logger LOG = LoggerFactory.getLogger(ProductRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_HIST_DAYS = 90;
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DataSource dataSource;
    // Stateless (no injected dependencies of its own) — instantiated directly, same
    // rationale as before the split (manual-classification-lock Phase 3).
    private final RubroResolver rubroResolver = new RubroResolver();

    ProductRepository(DataSource dataSource) {
        this.dataSource = dataSource;
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
    DatabaseService.UpsertStats upsertProductos(List<Product> productos) {
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
                return new DatabaseService.UpsertStats(nuevos, actualizados, sinCambios, desactivados);
            } catch (Exception e) {
                LOG.error("[DB] Error en upsert: {}", e.getMessage(), e);
                try { c.rollback(); } catch (Exception ignored) {}
                return new DatabaseService.UpsertStats(0, 0, 0, 0);
            }
        } catch (SQLException e) {
            LOG.error("[DB] Error en upsert: {}", e.getMessage(), e);
            return new DatabaseService.UpsertStats(0, 0, 0, 0);
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
    void upsertParcial(List<Product> productos) {
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

    List<Product> cargarProductos() {
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
    java.util.Optional<Product> obtenerProducto(String url) {
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
    Map<String, ClasificacionBloqueada> cargarClasificacionBloqueada() {
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
    void actualizarCategoria(String url, String nuevaCategoria) {
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
    int actualizarNormalizacion(String url, String categoria, String marca,
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
    boolean aplicarReclasificacionAuditada(String url, String categoria, String marca,
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



    long contarEmbeddings() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM image_embeddings")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            LOG.error("[DB] Error al contar image_embeddings", e);
            return 0L;
        }
    }

    void marcarDescontinuado(String url) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE productos SET activo=0 WHERE url=?")) {
            ps.setString(1, url);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error marcando descontinuado: {}", e.getMessage());
        }
    }

    boolean estaBloqueado(String url) {
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

    boolean esProductoActivo(String url) {
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

    /**
     * normalize-db-schema-fks-1nf, slice A.1 (design D9): the favourite-count
     * check and the DELETE now share this same transaction — a favourite
     * added between an endpoint-level pre-check and the delete would
     * otherwise turn the intended 409 into a raw FK-violation 500 (TOCTOU).
     * {@code DELETE FROM precio_historico} is gone: V4's {@code ON DELETE
     * CASCADE} on {@code precio_historico.url} covers it.
     */
    void limpiarProductos() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*) FROM favoritos f JOIN productos p ON p.url = f.url")) {
                    rs.next();
                    long bloqueantes = rs.getLong(1);
                    if (bloqueantes > 0) {
                        throw new FavoritosProtegidosException(bloqueantes);
                    }
                }
                st.execute("DELETE FROM productos");
                st.execute("DELETE FROM categoria_stats");
                c.commit();
                LOG.info("[DB] Catálogo, historial y stats de categorías eliminados.");
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }
}
