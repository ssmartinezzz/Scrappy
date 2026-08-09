package ar.scraper.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persistence for the {@code sitios_dinamicos} aggregate (sites added from the
 * dashboard, on top of the ones declared in {@code config.properties}).
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3).</p>
 */
class SitiosRepository {

    private static final Logger LOG = LoggerFactory.getLogger(SitiosRepository.class);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataSource dataSource;

    SitiosRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void guardarSitio(String nombre, String url, String plataforma) {
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

    void eliminarSitio(String nombre) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM sitios_dinamicos WHERE nombre=?")) {
            ps.setString(1, nombre);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando sitio: {}", e.getMessage());
        }
    }

    List<Map<String, String>> cargarSitiosDinamicos() {
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
}
