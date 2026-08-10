package ar.scraper.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads of the {@code precio_historico} aggregate.
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3). The
 * {@code HistorialEntry} record stays nested on DatabaseService — callers and
 * tests name it {@code DatabaseService.HistorialEntry}.</p>
 *
 * <p>Writes to this table are NOT here: they happen inside the product upsert
 * ({@code sp_upsert_run}) and its history pruning, which belong to the product
 * aggregate.</p>
 */
class HistorialRepository {

    private static final Logger LOG = LoggerFactory.getLogger(HistorialRepository.class);

    private final DataSource dataSource;

    HistorialRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    List<Map<String, Object>> cargarHistorial(String url) {
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

    List<DatabaseService.HistorialEntry> getHistorialPrecios(String url) {
        var result = new java.util.ArrayList<DatabaseService.HistorialEntry>();
        if (url == null) return result;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT fecha, precio FROM precio_historico WHERE url=? ORDER BY fecha")) {
            ps.setString(1, url);
            var rs = ps.executeQuery();
            while (rs.next())
                result.add(new DatabaseService.HistorialEntry(rs.getString("fecha"), rs.getDouble("precio")));
        } catch (Exception e) {
            LOG.warn("[DB] historial {}: {}", url, e.getMessage());
        }
        return result;
    }

    /**
     * Variante batch de {@link #getHistorialPrecios(String)}: carga el historial de
     * múltiples URLs en una sola consulta, evitando el patrón N+1 que resultaría de
     * llamar la versión single-URL por producto (usado por {@code SenalEnricher}
     * para precomputar señal de compra sobre todo el catálogo en un solo round-trip
     * a la DB).
     *
     * <p>El filtro va como {@code url = ANY(?)} con UN array bindeado, no como un
     * {@code IN (?,?,…)} con un placeholder por URL. La diferencia no es cosmética:</p>
     * <ul>
     *   <li>El SQL deja de depender del tamaño del lote. Con un placeholder por URL,
     *       cada tamaño distinto producía un texto de consulta distinto, así que
     *       Postgres nunca podía reutilizar un plan preparado.</li>
     *   <li>Desaparece el techo de 65535 parámetros del protocolo. Pasado ese punto
     *       el driver rechazaba la sentencia entera, y como el {@code catch} de abajo
     *       se traga la excepción, el catálogo entero quedaba "sin historial de
     *       precios" en silencio — sin señal de compra para ningún producto.</li>
     * </ul>
     *
     * @param urls URLs de productos a consultar; URLs vacías/blank son ignoradas
     * @return mapa url -&gt; historial (orden ascendente por fecha); URLs sin
     *         historial no aparecen como key
     */
    Map<String, List<DatabaseService.HistorialEntry>> getHistorialPrecios(List<String> urls) {
        Map<String, List<DatabaseService.HistorialEntry>> result = new HashMap<>();
        if (urls == null || urls.isEmpty()) return result;

        List<String> validUrls = urls.stream()
                .filter(u -> u != null && !u.isBlank())
                .distinct()
                .toList();
        if (validUrls.isEmpty()) return result;

        String sql = "SELECT url, fecha, precio FROM precio_historico " +
                "WHERE url = ANY(?) ORDER BY url, fecha";

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setArray(1, c.createArrayOf("text", validUrls.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String url = rs.getString("url");
                    result.computeIfAbsent(url, k -> new ArrayList<>())
                          .add(new DatabaseService.HistorialEntry(rs.getString("fecha"), rs.getDouble("precio")));
                }
            }
        } catch (Exception e) {
            LOG.warn("[DB] historial batch ({} urls): {}", validUrls.size(), e.getMessage());
        }
        return result;
    }
}
