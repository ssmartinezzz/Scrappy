package ar.scraper.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * Persistence for the {@code precios_externos} aggregate (MercadoLibre and
 * other off-catalog comparisons).
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3). {@code fecha}
 * is bound as {@link LocalDate} (design D6, normalize-db-schema-fks-1nf slice
 * A.2) — the column is {@code DATE} as of V5, and {@code ps.setString} binds a
 * varchar-typed parameter that has no operator against {@code date}.</p>
 */
class PreciosExternosRepository {

    private static final Logger LOG = LoggerFactory.getLogger(PreciosExternosRepository.class);

    private final DataSource dataSource;

    PreciosExternosRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void guardarPreciosExternos(String productoUrl, String sitio,
            java.util.List<java.util.Map<String,Object>> resultados) {
        if (resultados == null || resultados.isEmpty()) return;
        LocalDate hoy = LocalDate.now();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Borrar los del día para no duplicar
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM precios_externos WHERE producto_url=? AND sitio=? AND fecha=?")) {
                    // fecha is DATE (design D6) — ps.setString binds varchar and
                    // "date = character varying" has no operator; setObject(LocalDate)
                    // binds it as a real date parameter.
                    del.setString(1, productoUrl); del.setString(2, sitio); del.setObject(3, hoy);
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
                        ps.setObject(7, hoy);
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

    java.util.List<java.util.Map<String,Object>> cargarPreciosExternos(String productoUrl) {
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
}
