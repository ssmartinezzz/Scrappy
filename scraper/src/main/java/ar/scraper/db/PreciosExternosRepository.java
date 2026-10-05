package ar.scraper.db;

import ar.scraper.catalog.PreciosExternosPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.time.LocalDate;

@Repository
class PreciosExternosRepository implements PreciosExternosPort {

    private static final Logger LOG = LoggerFactory.getLogger(PreciosExternosRepository.class);

    private final JdbcTemplate jdbc;

    PreciosExternosRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void guardarPreciosExternos(String productoUrl, String sitio,
            java.util.List<java.util.Map<String,Object>> resultados) {
        if (resultados == null || resultados.isEmpty()) return;
        if (!isActiveProduct(productoUrl)) {
            LOG.warn("[DB] precios_externos ignorado: la url no es un producto activo del catálogo");
            return;
        }
        LocalDate hoy = LocalDate.now();
        try {
            jdbc.update("DELETE FROM precios_externos WHERE producto_url=? AND sitio=? AND fecha=?",
                    productoUrl, sitio, hoy);
            for (var r : resultados) {
                jdbc.update(
                        "INSERT INTO precios_externos (producto_url,sitio,titulo,precio,externo_url,condicion,fecha) VALUES(?,?,?,?,?,?,?)",
                        ps -> {
                            ps.setString(1, productoUrl);
                            ps.setString(2, sitio);
                            ps.setString(3, (String) r.getOrDefault("titulo", ""));
                            ps.setDouble(4, ((Number) r.getOrDefault("precio", 0.0)).doubleValue());
                            ps.setString(5, (String) r.getOrDefault("url", ""));
                            ps.setString(6, (String) r.getOrDefault("condicion", "new"));
                            ps.setObject(7, hoy);
                        });
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando precios_externos: {}", e.getMessage());
            Sql.marcarRollback();
        }
    }

    private boolean isActiveProduct(String url) {
        Boolean exists = jdbc.query("SELECT 1 FROM productos WHERE url=? AND activo IS NOT FALSE",
                ResultSet::next, url);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public java.util.List<java.util.Map<String,Object>> cargarPreciosExternos(String productoUrl) {
        var result = new java.util.ArrayList<java.util.Map<String,Object>>();
        try {
            jdbc.query(
                    "SELECT sitio,titulo,precio,externo_url,condicion,fecha " +
                    "FROM precios_externos WHERE producto_url=? ORDER BY fecha DESC, precio ASC LIMIT 20",
                    rs -> {
                        var row = new java.util.LinkedHashMap<String,Object>();
                        row.put("sitio",     rs.getString("sitio"));
                        row.put("titulo",    rs.getString("titulo"));
                        row.put("precio",    rs.getDouble("precio"));
                        row.put("url",       rs.getString("externo_url"));
                        row.put("condicion", rs.getString("condicion"));
                        row.put("fecha",     rs.getString("fecha"));
                        result.add(row);
                    }, productoUrl);
        } catch (Exception e) {
            LOG.warn("[DB] Error cargando precios_externos: {}", e.getMessage());
        }
        return result;
    }
}
