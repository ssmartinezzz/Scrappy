package ar.scraper.db;

import ar.scraper.catalog.HistorialEntry;
import ar.scraper.catalog.HistorialImportPort;
import ar.scraper.catalog.HistorialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

/**
 * Writes to this table are NOT here: they happen inside the product upsert ({@code sp_upsert_run})
 * and its history pruning, which belong to the product aggregate.
 */
@Repository
class HistorialRepository implements HistorialPort, HistorialImportPort {

    private static final Logger LOG = LoggerFactory.getLogger(HistorialRepository.class);

    private final JdbcTemplate jdbc;

    HistorialRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public List<Map<String, Object>> cargarHistorial(String url) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            jdbc.query("SELECT fecha, precio FROM precio_historico WHERE url=? ORDER BY fecha ASC",
                    rs -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("fecha",  rs.getString(1));
                        row.put("precio", rs.getDouble(2));
                        result.add(row);
                    }, url);
        } catch (Exception e) {
            LOG.warn("[DB] Error historial: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public List<HistorialEntry> getHistorialPrecios(String url) {
        var result = new ArrayList<HistorialEntry>();
        if (url == null) return result;
        try {
            jdbc.query("SELECT fecha, precio FROM precio_historico WHERE url=? ORDER BY fecha",
                    rs -> {
                        result.add(new HistorialEntry(rs.getString("fecha"), rs.getDouble("precio")));
                    }, url);
        } catch (Exception e) {
            LOG.warn("[DB] historial {}: {}", url, e.getMessage());
        }
        return result;
    }

    /**
     * Variante batch de {@link #getHistorialPrecios(String)}: carga el historial de múltiples URLs
     * en una sola consulta, evitando el patrón N+1 que resultaría de llamar la versión single-URL
     * por producto (usado por {@code SenalEnricher} para precomputar señal de compra sobre todo el
     * catálogo en un solo round-trip a la DB).
     */
    @Override
    public Map<String, List<HistorialEntry>> getHistorialPrecios(List<String> urls) {
        Map<String, List<HistorialEntry>> result = new HashMap<>();
        if (urls == null || urls.isEmpty()) return result;

        List<String> validUrls = urls.stream()
                .filter(u -> StringUtils.isNotBlank(u))
                .distinct()
                .toList();
        if (validUrls.isEmpty()) return result;

        String sql = "SELECT url, fecha, precio FROM precio_historico " +
                "WHERE url = ANY(?) ORDER BY url, fecha";

        try {
            jdbc.query(sql,
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", validUrls.toArray())),
                    rs -> {
                        String url = rs.getString("url");
                        result.computeIfAbsent(url, k -> new ArrayList<>())
                              .add(new HistorialEntry(rs.getString("fecha"), rs.getDouble("precio")));
                    });
        } catch (Exception e) {
            LOG.warn("[DB] historial batch ({} urls): {}", validUrls.size(), e.getMessage());
        }
        return result;
    }

    @Override
    public int importarCambios(Map<String, List<HistorialEntry>> cambiosPorUrl) {
        var filas = new ArrayList<Object[]>();
        cambiosPorUrl.forEach((url, cambios) -> cambios.forEach(c ->
                filas.add(new Object[] {url, c.precio(), c.fecha(), url})));
        if (filas.isEmpty()) return 0;
        // La FK a productos no admite huérfanos: el EXISTS descarta en silencio las urls ya borradas.
        String sql = "INSERT INTO precio_historico (url, precio, fecha) "
                + "SELECT ?, ?, ?::date WHERE EXISTS (SELECT 1 FROM productos WHERE url = ?) "
                + "ON CONFLICT (url, fecha) DO NOTHING";
        return jdbc.execute((ConnectionCallback<Integer>) con -> {
            boolean auto = con.getAutoCommit();
            con.setAutoCommit(false);
            try (var ps = con.prepareStatement(sql)) {
                for (Object[] f : filas) {
                    ps.setString(1, (String) f[0]);
                    ps.setDouble(2, (Double) f[1]);
                    ps.setString(3, (String) f[2]);
                    ps.setString(4, (String) f[3]);
                    ps.addBatch();
                }
                int insertadas = 0;
                for (int n : ps.executeBatch()) insertadas += Math.max(n, 0);
                con.commit();
                return insertadas;
            } catch (Exception e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(auto);
            }
        });
    }
}
