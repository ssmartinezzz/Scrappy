package ar.scraper.db;

import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteClassification;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.catalog.ClasificacionBloqueada;
import ar.scraper.catalog.FavoritosProtegidosException;
import ar.scraper.catalog.ProductPort;
import ar.scraper.catalog.UpsertStats;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.apache.commons.lang3.StringUtils;

@Repository
class ProductRepository implements ProductPort {

    private static final Logger LOG = LoggerFactory.getLogger(ProductRepository.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_HIST_DAYS = 90;
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DataSource dataSource;
    private final RubroResolver rubroResolver;
    private final SiteRegistry siteRegistry;
    private final TransactionTemplate tx;

    ProductRepository(DataSource dataSource, SiteRegistry siteRegistry, RubroResolver rubroResolver,
                      PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
        this.dataSource = dataSource;
        this.rubroResolver = rubroResolver;
        this.siteRegistry = siteRegistry;
    }

    @Override
    public UpsertStats upsertProductos(List<Product> productos) {
        return upsertProductos(productos, (ar.scraper.scrape.CorridaEnCurso) null);
    }

    /** {@code aggregator.agregar} hands over only the results it holds. */
    @Override
    public UpsertStats upsertProductos(List<Product> productos,
                                       ar.scraper.scrape.CorridaEnCurso corrida) {
        // The transaction is opened here, programmatically, so a failure to open (or close) one is
        // caught by this try.
        try {
            return tx.execute(status -> upsertEnTransaccion(productos, corrida, status));
        } catch (RuntimeException e) {
            LOG.warn("[DB] Upsert sin transacción: {}", e.getMessage());
            return new UpsertStats(0, 0, 0, 0);
        }
    }

    private UpsertStats upsertEnTransaccion(List<Product> productos,
                                            ar.scraper.scrape.CorridaEnCurso corrida,
                                            TransactionStatus status) {
        String now   = LocalDateTime.now().format(DT);
        String today = LocalDate.now().format(DATE);

        try (Connection c = dataSource.getConnection()) {
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

            // El alcance del soft-delete NO sale de la lista de sitios pedidos: un sitio cuyo
            // scraper se rompió llega con 0 productos, y no hay que confundir "se rompió" con "se
            // vació".
            Alcance alcance = corrida != null
                    ? alcanceDelRun(c, corrida)
                    : alcanceDelBatch(productos);
            int desactivados = softDeleteAusentes(c, alcance.urls(), now, alcance.sitios());

            purgarHistorialViejo(c);

            LOG.info("[DB] Upsert: {} nuevos / {} precio cambió / {} sin cambio / {} desactivados",
                    nuevos, actualizados, sinCambios, desactivados);
            return new UpsertStats(nuevos, actualizados, sinCambios, desactivados);
        } catch (Exception e) {
            LOG.error("[DB] Error en upsert: {}", e.getMessage(), e);
            status.setRollbackOnly();
            return new UpsertStats(0, 0, 0, 0);
        }
    }

    private String buildRowsJson(List<Product> productos, String now, String fecha, boolean includeVisual)
            throws Exception {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Product p : productos) {
            if (StringUtils.isBlank(p.url())) continue;
            ObjectNode row = arr.addObject();
            row.put("url", p.url());
            row.put("sitio", p.sitio());
            row.put("nombre", p.nombre());
            row.put("precio", p.precio());
            row.put("precioOrig", p.precioOriginal());
            row.put("imagenUrl", p.imagenUrl());
            row.put("categoria", p.categoria());
            row.put("genero", p.genero());
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
     * The two arrays {@code sp_soft_delete_ausentes} takes, kept together because widening one
     * without the other is the destructive failure the union exists to prevent.
     */
    private record Alcance(Set<String> urls, Set<String> sitios) {}

    /**
     * Read inside the caller's transaction and after {@code sp_upsert_run}, so this batch's own
     * rows are already stamped and included.
     */
    private Alcance alcanceDelRun(Connection c, ar.scraper.scrape.CorridaEnCurso corrida)
            throws SQLException {
        Set<String> urls   = new LinkedHashSet<>();
        Set<String> sitios = new LinkedHashSet<>();
        // One query still, so p_urls and p_sitios cannot widen apart.
        String sql = "SELECT p.url, p.sitio"
                   + "  FROM productos p"
                   + " WHERE p.touched_at >= ?"
                   + "   AND EXISTS (SELECT 1 FROM scrape_run_site s"
                   + "                WHERE s.scrape_run_id = ?"
                   + "                  AND s.sitio_key = p.sitio_key)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            // Bound as a parameter at UTC: a formatted literal would be read in the session zone,
            // which pgjdbc takes from the JVM, making the predicate depend on the machine the
            // backend runs on.
            ps.setObject(1, corrida.startedAt().truncatedTo(ChronoUnit.SECONDS)
                    .atOffset(ZoneOffset.UTC));
            ps.setLong(2, corrida.runId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String url   = rs.getString(1);
                    String sitio = rs.getString(2);
                    if (StringUtils.isNotBlank(url)) urls.add(url);
                    if (StringUtils.isNotBlank(sitio)) sitios.add(sitio);
                }
            }
        }
        return new Alcance(urls, sitios);
    }

    private Alcance alcanceDelBatch(List<Product> productos) {
        Set<String> urls   = new LinkedHashSet<>();
        Set<String> sitios = new LinkedHashSet<>();
        for (Product p : productos) {
            if (StringUtils.isBlank(p.url())) continue;
            urls.add(p.url());
            if (StringUtils.isNotBlank(p.sitio())) sitios.add(p.sitio());
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
            ps.setObject(1, cutoff);
            int deleted = ps.executeUpdate();
            if (deleted > 0) LOG.debug("[DB] Purged {} entradas historial > 90 dias", deleted);
        }
    }

    /** NUNCA hace soft-delete — solo inserta/actualiza los productos dados. */
    @Override
    public void upsertParcial(List<Product> productos) {
        if (productos == null || productos.isEmpty()) return;
        try {
            tx.executeWithoutResult(status -> upsertParcialEnTransaccion(productos, status));
        } catch (RuntimeException e) {
            LOG.warn("[DB] Error en upsertParcial: {}", e.getMessage());
        }
    }

    private void upsertParcialEnTransaccion(List<Product> productos, TransactionStatus status) {
        String now   = LocalDateTime.now().format(DT);
        String today = LocalDate.now().format(DATE);
        try (Connection c = dataSource.getConnection()) {
            String rowsJson = buildRowsJson(productos, now, today, false);
            try (PreparedStatement ps = c.prepareStatement("SELECT sp_upsert_run(?::jsonb, ?)")) {
                ps.setString(1, rowsJson);
                ps.setBoolean(2, false);
                ps.executeQuery().close();
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error en upsertParcial: {}", e.getMessage());
            status.setRollbackOnly();
        }
    }


    @Override
    public List<Product> cargarProductos() {
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
     * Delega en {@link #obtenerProducto} después de traducir handle -> url, a propósito: la carga
     * de talles y badges es idéntica y duplicarla sería dos caminos de lectura que pueden divergir.
     */
    @Override
    public java.util.Optional<Product> obtenerProductoPorKey(String key) {
        if (StringUtils.isBlank(key)) return java.util.Optional.empty();
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

    @Override
    public java.util.Optional<Product> obtenerProducto(String url) {
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

    /** Read-side of the manual classification lock. */
    @Override
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


    /**
     * Lleva {@code AND bloqueado_por IS NULL}: un producto bloqueado no debe perder su categoría
     * humana-confirmada por este camino.
     */
    @Override
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
     * {@code respectLock} decide si se agrega el guard {@code AND bloqueado_por IS NULL} a la
     * sentencia de clasificación — {@code false} para el camino humano (una segunda confirmación
     * debe poder re-lockear un producto ya bloqueado), {@code true} para el de máquina.
     */
    private int updateNormalizacion(Connection c, String url, String categoria, String marca,
                                     String genero, List<String> talles, String subCategoria,
                                     boolean respectLock) throws Exception {
        reemplazarTalles(c, url, talles);
        String sql = "UPDATE productos SET categoria=?, marca=?, genero=?, sub_categoria=? WHERE url=?"
                + (respectLock ? " AND bloqueado_por IS NULL" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, categoria != null ? categoria : "");
            if (StringUtils.isBlank(marca)) ps.setNull(2, java.sql.Types.VARCHAR);
            else ps.setString(2, marca);
            ps.setString(3, genero != null ? genero : "");
            ps.setString(4, subCategoria != null ? subCategoria : "");
            ps.setString(5, url);
            return ps.executeUpdate();
        }
    }

    /**
     * DELETE + INSERT, nunca {@code ON CONFLICT}: una lista de talles que se ACHICA no puede dejar
     * filas viejas atrás — es exactamente lo que significaba que {@code talles} fuera OVERWRITTEN y
     * no fill-only.
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
                if (StringUtils.isBlank(talle)) continue;
                ps.setString(1, url);
                ps.setShort(2, posicion++);
                ps.setString(3, talle);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Actualiza categoria/marca/genero/talles de un producto ya existente en la DB sin re-scrapear.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int actualizarNormalizacion(String url, String categoria, String marca,
                                        String genero, List<String> talles, String subCategoria) {
        if (url == null) return 0;
        try (Connection c = dataSource.getConnection()) {
            return updateNormalizacion(c, url, categoria, marca, genero, talles, subCategoria, true);
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando normalizacion: {}", e.getMessage());
            Sql.marcarRollback();
            return 0;
        }
    }

    /**
     * Una sola conexión, una sola transacción: el UPDATE de {@link #updateNormalizacion}, el UPDATE
     * de {@code rubro}/lock y el INSERT de auditoría se confirman juntos o ninguno de los tres.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean aplicarReclasificacionAuditada(String url, String categoria, String marca,
                                                   String genero, List<String> talles, String subCategoria,
                                                   Product previo, String actor) {
        if (url == null) return false;
        String sitioKey = SiteClassification.sitioKey(previo != null ? previo.sitio() : "");
        String rubroPrevio = previo != null ? previo.rubro() : null;
        String rubro = rubroResolver.resolver(sitioKey, categoria, rubroPrevio);
        java.time.OffsetDateTime ahora = Timestamps.now();

        try (Connection c = dataSource.getConnection()) {
            int rows = updateNormalizacion(c, url, categoria, marca, genero, talles, subCategoria, false);
            if (rows != 1) {
                Sql.marcarRollback();
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE productos SET rubro=?, bloqueado_por=?, bloqueado_at=? WHERE url=?")) {
                ps.setString(1, rubro != null ? rubro : "indumentaria");
                ps.setString(2, StringUtils.isNotBlank(actor) ? actor : "local");
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
                ps.setString(11, StringUtils.isNotBlank(actor) ? actor : "local");
                ps.executeUpdate();
            }
            return true;
        } catch (Exception e) {
            LOG.error("[DB] Error en aplicarReclasificacionAuditada, rollback: {}", e.getMessage(), e);
            Sql.marcarRollback();
            return false;
        }
    }

    @Override
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

    @Override
    public void marcarDescontinuado(String url) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE productos SET activo=false WHERE url=?")) {
            ps.setString(1, url);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error marcando descontinuado: {}", e.getMessage());
        }
    }

    @Override
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

    @Override
    public boolean esProductoActivo(String url) {
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void limpiarProductos() {
        Sql.traducir(() -> limpiarProductosSql());
    }

    private void limpiarProductosSql() throws SQLException {
        try (Connection c = dataSource.getConnection();
             var st = c.createStatement()) {
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
            LOG.info("[DB] Catálogo, historial y stats de categorías eliminados.");
        }
    }
}
