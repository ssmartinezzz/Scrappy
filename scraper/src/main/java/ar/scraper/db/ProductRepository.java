package ar.scraper.db;

import ar.scraper.aggregator.normalize.RubroResolver;
import ar.scraper.aggregator.normalize.SiteClassification;
import ar.scraper.aggregator.normalize.SiteRegistry;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
    // Not Spring-managed (constructed directly by DatabaseService, same rationale
    // as before the split, manual-classification-lock Phase 3) — takes the shared
    // SiteRegistry passed down from DatabaseService instead of resolving its own.
    private final RubroResolver rubroResolver;
    private final SiteRegistry siteRegistry;

    ProductRepository(DataSource dataSource, SiteRegistry siteRegistry) {
        this.dataSource = dataSource;
        this.rubroResolver = new RubroResolver(siteRegistry);
        this.siteRegistry = siteRegistry;
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
        return upsertProductos(productos, null);
    }

    /**
     * Same merge, with the soft-delete scope derived from the run instead of
     * from this batch (design D4).
     *
     * <p><b>The problem.</b> {@code aggregator.agregar} hands over only the
     * results it holds. On a resumed run that is the resumed half alone, so a
     * batch-derived scope stops sweeping every site the interrupted half had
     * covered, and their stale rows stay {@code activo} forever.</p>
     *
     * <p><b>Why not simply widen {@code p_sitios}.</b> Widening the site list
     * while {@code p_urls} still held only the resumed half's URLs would make
     * every product of the other sites look absent, and
     * {@code sp_soft_delete_ausentes} would deactivate them <i>entirely</i> —
     * strictly worse than the bug being fixed. Both arrays have to widen
     * together, which is exactly what deriving them from one query guarantees.</p>
     *
     * <p><b>Why {@code touched_at} and not a run id.</b> "Everything this run
     * saw" is already recorded: {@code upsertParcial} commits each site's rows
     * as it finishes, stamping {@code touched_at} from the same Java clock.
     * Reading it back spans both halves without a {@code scrape_run_id} column
     * on the hottest table in the schema, and without passing a run id into
     * {@code sp_soft_delete_ausentes} — whose body stays byte-identical to
     * {@code V5}, as {@code StoredProcedureDriftTest} asserts.</p>
     *
     * <p><b>The bound is inclusive on purpose.</b> {@code touched_at} is written
     * through a whole-second format ({@link #DT}), and
     * {@code ScrapeRunRepository.crear} truncates {@code started_at} to match.
     * Rows written during the run's own first second therefore compare equal and
     * {@code >=} keeps them. An exclusive bound — or a sub-second
     * {@code started_at} — would read them as absent and soft-delete products
     * the run had just written.</p>
     *
     * @param runStartedAt the run's {@code started_at}, or {@code null} when the
     *                     caller has no run; then the scope falls back to the
     *                     batch, behaving exactly as it did before this change.
     */
    DatabaseService.UpsertStats upsertProductos(List<Product> productos, Instant runStartedAt) {
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

                // El alcance del soft-delete NO sale de la lista de sitios
                // pedidos: un sitio cuyo scraper se rompió llega con 0
                // productos, y no hay que confundir "se rompió" con "se vació".
                // Sale de lo que la corrida efectivamente tocó — de la base
                // cuando hay run, del batch cuando no.
                Alcance alcance = runStartedAt != null
                        ? alcanceDelRun(c, runStartedAt)
                        : alcanceDelBatch(productos);
                int desactivados = softDeleteAusentes(c, alcance.urls(), now, alcance.sitios());

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
            // talles/mlBadges travel as real JSON arrays: sp_upsert_run explodes
            // them into producto_talle/producto_badge with WITH ORDINALITY (V7).
            // Serializing a list into a string here and splitting it there is what
            // the child tables exist to stop doing.
            ArrayNode tallesJson = row.putArray("talles");
            if (p.talles() != null) p.talles().forEach(tallesJson::add);
            ArrayNode badgesJson = row.putArray("mlBadges");
            if (p.ml() != null && p.ml().badges() != null) p.ml().badges().forEach(badgesJson::add);
            row.put("mlScore", p.ml() != null ? p.ml().scoreP() : 50);
            row.put("mlOferta", p.ml() != null && p.ml().ofertaReal());
            row.put("mlTendencia", p.ml() != null ? p.ml().tendencia() : "");
            row.put("mlSegment", p.ml() != null ? p.ml().segment() : "standard");
            row.put("mlZscore", p.ml() != null ? p.ml().zScore() : 0.0);
            row.put("rubro", p.rubro() != null ? p.rubro() : "indumentaria");
            row.put("marca", p.marca() != null ? p.marca() : "");
            row.put("gymrat", p.gymrat());
            row.put("marcaPremium", p.marcaPremium());
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

    /**
     * The two arrays {@code sp_soft_delete_ausentes} takes, kept together
     * because widening one without the other is the destructive failure the
     * union exists to prevent.
     */
    private record Alcance(Set<String> urls, Set<String> sitios) {}

    /**
     * Everything the run has written so far, across every half it completed.
     *
     * <p>Read inside the caller's transaction and after {@code sp_upsert_run},
     * so this batch's own rows are already stamped and included.</p>
     */
    private Alcance alcanceDelRun(Connection c, Instant runStartedAt) throws SQLException {
        Set<String> urls   = new LinkedHashSet<>();
        Set<String> sitios = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT url, sitio FROM productos WHERE touched_at >= ?")) {
            // Bound as a parameter at UTC: a formatted literal would be read in
            // the session zone, which pgjdbc takes from the JVM, making the
            // predicate depend on the machine the backend runs on.
            ps.setObject(1, runStartedAt.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String url   = rs.getString(1);
                    String sitio = rs.getString(2);
                    if (url != null && !url.isBlank()) urls.add(url);
                    if (sitio != null && !sitio.isBlank()) sitios.add(sitio);
                }
            }
        }
        return new Alcance(urls, sitios);
    }

    /** Pre-D4 behaviour, kept for callers that have no run to scope by. */
    private Alcance alcanceDelBatch(List<Product> productos) {
        Set<String> urls   = new LinkedHashSet<>();
        Set<String> sitios = new LinkedHashSet<>();
        for (Product p : productos) {
            if (p.url() == null || p.url().isBlank()) continue;
            urls.add(p.url());
            if (p.sitio() != null && !p.sitio().isBlank()) sitios.add(p.sitio());
        }
        return new Alcance(urls, sitios);
    }

    private int softDeleteAusentes(Connection c, Set<String> urlsPresentes, String now,
                                   Set<String> sitiosPresentes) throws SQLException {
        if (sitiosPresentes.isEmpty()) return 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT sp_soft_delete_ausentes(?, ?, ?)")) {
            Array urlArray = c.createArrayOf("text", urlsPresentes.toArray());
            ps.setArray(1, urlArray);
            ps.setString(2, now);
            ps.setArray(3, c.createArrayOf("text", sitiosPresentes.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void purgarHistorialViejo(Connection c) throws SQLException {
        LocalDate cutoff = LocalDate.now().minusDays(MAX_HIST_DAYS);
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM precio_historico WHERE fecha < ? " +
                "AND url NOT IN (SELECT url FROM favoritos)")) {
            // fecha is DATE (design D6) — ps.setString binds a varchar-typed
            // parameter and "date < character varying" has no operator;
            // ps.setObject(LocalDate) binds it as a real date parameter.
            ps.setObject(1, cutoff);
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


    /**
     * Tres sentencias en total, constantes en el tamaño del catálogo (design D3):
     * las dos tablas hijas se leen enteras, planas y ordenadas ANTES del loop
     * principal y se mergean por url en memoria. Un lookup por producto serían
     * 27086 round trips sobre 13543 filas; un {@code LEFT JOIN … array_agg}
     * obligaría a {@code obtenerProducto()} y a este método a divergir.
     */
    List<Product> cargarProductos() {
        List<Product> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            Map<String, List<String>> tallesPorUrl = cargarMultivalor(c, "producto_talle", "talle");
            Map<String, List<String>> badgesPorUrl = cargarMultivalor(c, "producto_badge", "badge");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         ProductRowMapper.COLUMNAS + " WHERE activo ORDER BY precio ASC")) {
                while (rs.next()) {
                    String url = rs.getString("url");
                    result.add(ProductRowMapper.map(rs,
                            tallesPorUrl.getOrDefault(url, List.of()),
                            badgesPorUrl.getOrDefault(url, List.of()), siteRegistry));
                }
            }
            LOG.info("[DB] Cargados {} productos activos", result.size());
        } catch (Exception e) {
            LOG.error("[DB] Error cargando productos: {}", e.getMessage(), e);
        }
        return result;
    }

    /** Busca un producto por URL sin filtrar por `activo` (incluye descontinuados). */
    /**
     * Resuelve un producto por su handle corto ({@code producto_key}, la columna
     * generada de V25) en vez de por su URL entera.
     *
     * <p>Delega en {@link #obtenerProducto} después de traducir handle -> url,
     * a propósito: la carga de talles y badges es idéntica y duplicarla sería
     * dos caminos de lectura que pueden divergir. El índice único sobre
     * {@code producto_key} hace que la traducción sea una búsqueda, no un scan.</p>
     */
    java.util.Optional<Product> obtenerProductoPorKey(String key) {
        if (key == null || key.isBlank()) return java.util.Optional.empty();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT url FROM productos WHERE producto_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return java.util.Optional.empty();
                return obtenerProducto(rs.getString(1));
            }
        } catch (Exception e) {
            LOG.error("[DB] Error resolviendo producto_key {}: {}", key, e.getMessage(), e);
            return java.util.Optional.empty();
        }
    }

    java.util.Optional<Product> obtenerProducto(String url) {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(ProductRowMapper.COLUMNAS + " WHERE url=?")) {
                ps.setString(1, url);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return java.util.Optional.empty();
                    return java.util.Optional.of(ProductRowMapper.map(rs,
                            cargarMultivalor(c, "producto_talle", "talle", url),
                            cargarMultivalor(c, "producto_badge", "badge", url), siteRegistry));
                }
            }
        } catch (Exception e) {
            LOG.error("[DB] Error obteniendo producto {}: {}", url, e.getMessage(), e);
            return java.util.Optional.empty();
        }
    }

    /** Toda una tabla hija, plana y ordenada por (url, posicion), agrupada por url. */
    private Map<String, List<String>> cargarMultivalor(Connection c, String tabla, String columna)
            throws SQLException {
        Map<String, List<String>> porUrl = new HashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT url," + columna + " FROM " + tabla + " ORDER BY url, posicion")) {
            while (rs.next()) {
                porUrl.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
            }
        }
        return porUrl;
    }

    /** Los valores de UN producto, en orden. */
    private List<String> cargarMultivalor(Connection c, String tabla, String columna, String url)
            throws SQLException {
        List<String> valores = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + columna + " FROM " + tabla + " WHERE url=? ORDER BY posicion")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) valores.add(rs.getString(1));
            }
        }
        return valores;
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
        reemplazarTalles(c, url, talles);
        String sql = "UPDATE productos SET categoria=?, marca=?, genero=?, sub_categoria=? WHERE url=?"
                + (respectLock ? " AND bloqueado_por IS NULL" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, categoria != null ? categoria : "");
            // marca: "" es el centinela de abstención de BrandExtractor, y la FK
            // fk_productos_marca (V21) no puede referenciarlo — una FK afirma una
            // REFERENCIA, y no hay marca que referenciar cuando el extractor no supo.
            // El contrato lo fija el header de V21: NULL en la base, "" en el borde
            // Java, y es exactamente lo que hace sp_upsert_run con
            // nullif(r->>'marca',''). Este path escribía "" literal, así que
            // reclasificar un producto sin marca reventaba contra la FK y el rollback
            // devolvía false — el agente "confirmaba" un cambio que nunca ocurría.
            // isBlank() y no isEmpty(): " " tampoco es una fila de `marca`, así que
            // dejarla pasar sería el mismo choque por otra puerta.
            if (marca == null || marca.isBlank()) ps.setNull(2, java.sql.Types.VARCHAR);
            else ps.setString(2, marca);
            ps.setString(3, genero != null ? genero : "");
            ps.setString(4, subCategoria != null ? subCategoria : "");
            ps.setString(5, url);
            return ps.executeUpdate();
        }
    }

    /**
     * DELETE + INSERT, nunca {@code ON CONFLICT} (design D4): una lista de
     * talles que se ACHICA no puede dejar filas viejas atrás — es exactamente
     * lo que significaba que {@code talles} fuera OVERWRITTEN y no fill-only.
     * Misma semántica que la sentencia propia y sin guard que tenía antes: los
     * talles se escriben aunque el producto esté bloqueado (review fix F2).
     *
     * <p>Las posiciones son contiguas y arrancan en 1 — un talle en blanco se
     * descarta sin consumir posición, igual que hace el backfill de V7.</p>
     *
     * <p>Sobre una url INEXISTENTE con lista no vacía, el INSERT viola la FK a
     * {@code productos(url)} en vez de ser un UPDATE de 0 filas silencioso como
     * antes. Los dos contratos publicados se mantienen igual —
     * {@link #actualizarNormalizacion} devuelve 0, {@link #aplicarReclasificacionAuditada}
     * hace rollback y devuelve {@code false}— y ninguna fila huérfana queda:
     * lo único que cambia es que ahora queda un log del intento
     * ({@code TallesRoundTripTest}).</p>
     */
    private void reemplazarTalles(Connection c, String url, List<String> talles) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM producto_talle WHERE url=?")) {
            ps.setString(1, url);
            ps.executeUpdate();
        }
        if (talles == null || talles.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO producto_talle (url, posicion, talle) VALUES (?,?,?)")) {
            short posicion = 1;
            for (String talle : talles) {
                if (talle == null || talle.isBlank()) continue;
                ps.setString(1, url);
                ps.setShort(2, posicion++);
                ps.setString(3, talle);
                ps.addBatch();
            }
            ps.executeBatch();
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
        java.time.OffsetDateTime ahora = Timestamps.now();

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
                    ps.setObject(3, ahora);
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
                    ps.setObject(10, ahora);
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
                "UPDATE productos SET activo=false WHERE url=?")) {
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
                return rs.next() && rs.getBoolean(1);
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
