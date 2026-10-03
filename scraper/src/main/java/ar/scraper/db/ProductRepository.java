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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    private final JdbcTemplate jdbc;
    private final RubroResolver rubroResolver;
    private final SiteRegistry siteRegistry;
    private final TransactionTemplate tx;

    ProductRepository(DataSource dataSource, SiteRegistry siteRegistry, RubroResolver rubroResolver,
                      PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
        this.jdbc = new JdbcTemplate(dataSource);
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

        try {
            String rowsJson = buildRowsJson(productos, now, today, true);

            UpsertStats upsert = upsertRun(rowsJson, true);

            // El alcance del soft-delete NO sale de la lista de sitios pedidos: un sitio cuyo
            // scraper se rompió llega con 0 productos, y no hay que confundir "se rompió" con "se
            // vació".
            Alcance alcance = corrida != null
                    ? alcanceDelRun(corrida)
                    : alcanceDelBatch(productos);
            int desactivados = softDeleteAusentes(alcance.urls(), now, alcance.sitios());

            purgarHistorialViejo();

            LOG.info("[DB] Upsert: {} nuevos / {} precio cambió / {} sin cambio / {} desactivados",
                    upsert.nuevos(), upsert.actualizados(), upsert.sinCambios(), desactivados);
            return new UpsertStats(upsert.nuevos(), upsert.actualizados(), upsert.sinCambios(), desactivados);
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
    private Alcance alcanceDelRun(ar.scraper.scrape.CorridaEnCurso corrida) {
        Set<String> urls   = new LinkedHashSet<>();
        Set<String> sitios = new LinkedHashSet<>();
        // One query still, so p_urls and p_sitios cannot widen apart.
        String sql = "SELECT p.url, p.sitio"
                   + "  FROM productos p"
                   + " WHERE p.touched_at >= ?"
                   + "   AND EXISTS (SELECT 1 FROM scrape_run_site s"
                   + "                WHERE s.scrape_run_id = ?"
                   + "                  AND s.sitio_key = p.sitio_key)";
        jdbc.query(sql, ps -> {
            // Bound as a parameter at UTC: a formatted literal would be read in the session zone,
            // which pgjdbc takes from the JVM, making the predicate depend on the machine the
            // backend runs on.
            ps.setObject(1, corrida.startedAt().truncatedTo(ChronoUnit.SECONDS)
                    .atOffset(ZoneOffset.UTC));
            ps.setLong(2, corrida.runId());
        }, rs -> {
            String url   = rs.getString(1);
            String sitio = rs.getString(2);
            if (StringUtils.isNotBlank(url)) urls.add(url);
            if (StringUtils.isNotBlank(sitio)) sitios.add(sitio);
        });
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

    private int softDeleteAusentes(Set<String> urlsPresentes, String now, Set<String> sitiosPresentes) {
        if (sitiosPresentes.isEmpty()) return 0;
        return jdbc.query("SELECT sp_soft_delete_ausentes(?, ?, ?)", ps -> {
            ps.setArray(1, ps.getConnection().createArrayOf("text", urlsPresentes.toArray()));
            ps.setString(2, now);
            ps.setArray(3, ps.getConnection().createArrayOf("text", sitiosPresentes.toArray()));
        }, rs -> rs.next() ? rs.getInt(1) : 0);
    }

    private void purgarHistorialViejo() {
        LocalDate cutoff = LocalDate.now().minusDays(MAX_HIST_DAYS);
        int deleted = jdbc.update(
                "DELETE FROM precio_historico WHERE fecha < ? " +
                "AND url NOT IN (SELECT url FROM favoritos)",
                ps -> ps.setObject(1, cutoff));
        if (deleted > 0) LOG.debug("[DB] Purged {} entradas historial > 90 dias", deleted);
    }

    /** NUNCA hace soft-delete — solo inserta/actualiza los productos dados. */
    @Override
    public UpsertStats upsertParcial(List<Product> productos) {
        if (productos == null || productos.isEmpty()) return UpsertStats.CERO;
        try {
            return tx.execute(status -> upsertParcialEnTransaccion(productos, status));
        } catch (RuntimeException e) {
            LOG.warn("[DB] Error en upsertParcial: {}", e.getMessage());
            return UpsertStats.CERO;
        }
    }

    private UpsertStats upsertParcialEnTransaccion(List<Product> productos, TransactionStatus status) {
        String now   = LocalDateTime.now().format(DT);
        String today = LocalDate.now().format(DATE);
        try {
            return upsertRun(buildRowsJson(productos, now, today, false), false);
        } catch (Exception e) {
            LOG.warn("[DB] Error en upsertParcial: {}", e.getMessage());
            status.setRollbackOnly();
            return UpsertStats.CERO;
        }
    }

    /** {@code sp_upsert_run} counts what it changed; the soft-delete is the caller's. */
    private UpsertStats upsertRun(String rowsJson, boolean includeVisual) throws Exception {
        List<String> filas = new ArrayList<>();
        jdbc.query("SELECT sp_upsert_run(?::jsonb, ?)", ps -> {
            ps.setString(1, rowsJson);
            ps.setBoolean(2, includeVisual);
        }, rs -> {
            filas.add(rs.getString(1));
        });
        if (filas.isEmpty()) return UpsertStats.CERO;
        JsonNode stats = MAPPER.readTree(filas.get(0));
        return new UpsertStats(stats.path("nuevos").asInt(0), stats.path("actualizados").asInt(0),
                stats.path("sinCambios").asInt(0), 0);
    }


    @Override
    public List<Product> cargarProductos() {
        List<Product> result = new ArrayList<>();
        try {
            Map<String, List<String>> tallesPorUrl = cargarMultivalor("producto_talle", "talle");
            Map<String, List<String>> badgesPorUrl = cargarMultivalor("producto_badge", "badge");
            jdbc.query(ProductRowMapper.COLUMNAS + " WHERE activo ORDER BY precio ASC", rs -> {
                String url = rs.getString("url");
                result.add(ProductRowMapper.map(rs,
                        tallesPorUrl.getOrDefault(url, List.of()),
                        badgesPorUrl.getOrDefault(url, List.of()), siteRegistry));
            });
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
        try {
            String url = jdbc.query("SELECT url FROM productos WHERE producto_key = ?",
                    ps -> ps.setString(1, key), rs -> rs.next() ? rs.getString(1) : null);
            if (url == null) return java.util.Optional.empty();
            return obtenerProducto(url);
        } catch (Exception e) {
            LOG.error("[DB] Error resolviendo producto_key {}: {}", key, e.getMessage(), e);
            return java.util.Optional.empty();
        }
    }

    @Override
    public java.util.Optional<Product> obtenerProducto(String url) {
        try {
            // One connection across the row and its two child lookups, as before: a nested
            // JdbcTemplate call would check a second connection out while the first is still held.
            return jdbc.execute((ConnectionCallback<java.util.Optional<Product>>) c -> {
                try (PreparedStatement ps = c.prepareStatement(ProductRowMapper.COLUMNAS + " WHERE url=?")) {
                    ps.setString(1, url);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) return java.util.Optional.empty();
                        return java.util.Optional.of(ProductRowMapper.map(rs,
                                cargarMultivalor(c, "producto_talle", "talle", url),
                                cargarMultivalor(c, "producto_badge", "badge", url), siteRegistry));
                    }
                }
            });
        } catch (Exception e) {
            LOG.error("[DB] Error obteniendo producto {}: {}", url, e.getMessage(), e);
            return java.util.Optional.empty();
        }
    }

    private Map<String, List<String>> cargarMultivalor(String tabla, String columna) {
        Map<String, List<String>> porUrl = new HashMap<>();
        jdbc.query("SELECT url," + columna + " FROM " + tabla + " ORDER BY url, posicion", rs -> {
            porUrl.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
        });
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
        try {
            jdbc.query("SELECT url,categoria,sub_categoria,marca,genero,rubro FROM productos "
                       + "WHERE bloqueado_por IS NOT NULL", rs -> {
                result.put(rs.getString("url"), new ClasificacionBloqueada(
                        rs.getString("categoria"),
                        rs.getString("sub_categoria"),
                        rs.getString("marca"),
                        rs.getString("genero"),
                        rs.getString("rubro")));
            });
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
        try {
            jdbc.update("UPDATE productos SET categoria=? WHERE url=? AND bloqueado_por IS NULL", ps -> {
                ps.setString(1, nuevaCategoria);
                ps.setString(2, url);
            });
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando categoria: {}", e.getMessage());
        }
    }

    /**
     * {@code respectLock} decide si se agrega el guard {@code AND bloqueado_por IS NULL} a la
     * sentencia de clasificación — {@code false} para el camino humano (una segunda confirmación
     * debe poder re-lockear un producto ya bloqueado), {@code true} para el de máquina.
     */
    private int updateNormalizacion(String url, String categoria, String marca,
                                     String genero, List<String> talles, String subCategoria,
                                     boolean respectLock) {
        reemplazarTalles(url, talles);
        String sql = "UPDATE productos SET categoria=?, marca=?, genero=?, sub_categoria=? WHERE url=?"
                + (respectLock ? " AND bloqueado_por IS NULL" : "");
        return jdbc.update(sql, ps -> {
            ps.setString(1, categoria != null ? categoria : "");
            if (StringUtils.isBlank(marca)) ps.setNull(2, java.sql.Types.VARCHAR);
            else ps.setString(2, marca);
            ps.setString(3, genero != null ? genero : "");
            ps.setString(4, subCategoria != null ? subCategoria : "");
            ps.setString(5, url);
        });
    }

    /**
     * DELETE + INSERT, nunca {@code ON CONFLICT}: una lista de talles que se ACHICA no puede dejar
     * filas viejas atrás — es exactamente lo que significaba que {@code talles} fuera OVERWRITTEN y
     * no fill-only.
     */
    private void reemplazarTalles(String url, List<String> talles) {
        jdbc.update("DELETE FROM producto_talle WHERE url=?", url);
        if (talles == null || talles.isEmpty()) return;
        List<String> noVacios = talles.stream().filter(StringUtils::isNotBlank).toList();
        if (noVacios.isEmpty()) return;
        short[] posicion = {1};
        jdbc.batchUpdate("INSERT INTO producto_talle (url, posicion, talle) VALUES (?,?,?)",
                noVacios, noVacios.size(), (ps, talle) -> {
                    ps.setString(1, url);
                    ps.setShort(2, posicion[0]++);
                    ps.setString(3, talle);
                });
    }

    /**
     * Actualiza categoria/marca/genero/talles de un producto ya existente en la DB sin re-scrapear.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int actualizarNormalizacion(String url, String categoria, String marca,
                                        String genero, List<String> talles, String subCategoria) {
        if (url == null) return 0;
        try {
            return updateNormalizacion(url, categoria, marca, genero, talles, subCategoria, true);
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

        try {
            int rows = updateNormalizacion(url, categoria, marca, genero, talles, subCategoria, false);
            if (rows != 1) {
                Sql.marcarRollback();
                return false;
            }
            jdbc.update("UPDATE productos SET rubro=?, bloqueado_por=?, bloqueado_at=? WHERE url=?", ps -> {
                ps.setString(1, rubro != null ? rubro : "indumentaria");
                ps.setString(2, StringUtils.isNotBlank(actor) ? actor : "local");
                ps.setObject(3, ahora);
                ps.setString(4, url);
            });
            jdbc.update(
                    "INSERT INTO agent_reclassify_audit " +
                    "(url, categoria_antes, categoria_despues, marca_antes, marca_despues, " +
                    "genero_antes, genero_despues, sub_categoria_antes, sub_categoria_despues, " +
                    "applied_at, applied_by) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?)", ps -> {
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
            });
            return true;
        } catch (Exception e) {
            LOG.error("[DB] Error en aplicarReclasificacionAuditada, rollback: {}", e.getMessage(), e);
            Sql.marcarRollback();
            return false;
        }
    }

    @Override
    public long contarEmbeddings() {
        try {
            return jdbc.query("SELECT COUNT(*) FROM image_embeddings", rs -> rs.next() ? rs.getLong(1) : 0L);
        } catch (DataAccessException e) {
            LOG.error("[DB] Error al contar image_embeddings", e);
            return 0L;
        }
    }

    @Override
    public void marcarDescontinuado(String url) {
        try {
            jdbc.update("UPDATE productos SET activo=false WHERE url=?", url);
        } catch (Exception e) {
            LOG.warn("[DB] Error marcando descontinuado: {}", e.getMessage());
        }
    }

    @Override
    public boolean estaBloqueado(String url) {
        try {
            return jdbc.query("SELECT 1 FROM productos WHERE url=? AND bloqueado_por IS NOT NULL",
                    ps -> ps.setString(1, url), ResultSet::next);
        } catch (Exception e) {
            LOG.warn("[DB] Error consultando bloqueo de {}: {}", url, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean esProductoActivo(String url) {
        try {
            return jdbc.query("SELECT activo FROM productos WHERE url=?",
                    ps -> ps.setString(1, url), rs -> rs.next() && rs.getBoolean(1));
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

    private void limpiarProductosSql() {
        Long bloqueantes = jdbc.query("SELECT COUNT(*) FROM favoritos f JOIN productos p ON p.url = f.url",
                rs -> {
                    rs.next();
                    return rs.getLong(1);
                });
        if (bloqueantes != null && bloqueantes > 0) {
            throw new FavoritosProtegidosException(bloqueantes);
        }
        jdbc.update("DELETE FROM productos");
        jdbc.update("DELETE FROM categoria_stats");
        LOG.info("[DB] Catálogo, historial y stats de categorías eliminados.");
    }
}
