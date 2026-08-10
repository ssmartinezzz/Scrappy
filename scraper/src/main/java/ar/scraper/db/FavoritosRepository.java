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
 * Persistence for the {@code favoritos} aggregate.
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3).</p>
 */
class FavoritosRepository {

    private static final Logger LOG = LoggerFactory.getLogger(FavoritosRepository.class);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DataSource dataSource;

    FavoritosRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void guardarFavorito(String url, String sitio, String nombre) {
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

    void eliminarFavorito(String url) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM favoritos WHERE url=?")) {
            ps.setString(1, url);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando favorito: {}", e.getMessage());
        }
    }

    List<Map<String, String>> listarFavoritos() {
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

    void tocarFavorito(String url) {
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
}
