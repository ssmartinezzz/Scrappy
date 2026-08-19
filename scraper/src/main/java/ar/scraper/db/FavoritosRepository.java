package ar.scraper.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persistence for the {@code favoritos} aggregate.
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3).</p>
 *
 * <p><b>The {@code WHERE usuario_id IS NULL} in the upsert is load-bearing.</b>
 * {@code V26} dropped {@code favoritos}' {@code url} primary key, so the only
 * unique index left over {@code url} alone is the partial one covering rows
 * with no owner. Postgres will not infer a partial index implicitly: the
 * {@code ON CONFLICT} clause has to repeat its predicate, or the statement is
 * rejected outright — the first insert included, not only a conflicting one.
 * Dropping the predicate here fails as "no favourites saved" rather than as an
 * error, because the methods below log and swallow.</p>
 */
class FavoritosRepository {

    private static final Logger LOG = LoggerFactory.getLogger(FavoritosRepository.class);

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
                    ON CONFLICT (url) WHERE usuario_id IS NULL
                    DO UPDATE SET sitio=excluded.sitio, nombre=excluded.nombre
                    """)) {
            ps.setString(1, url);
            ps.setString(2, sitio);
            ps.setString(3, nombre);
            ps.setObject(4, Timestamps.now());
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
                row.put("added_at",       Timestamps.iso(rs, "added_at"));
                row.put("last_checked_at", Timestamps.iso(rs, "last_checked_at"));
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
            ps.setObject(1, Timestamps.now());
            ps.setString(2, url);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando last_checked_at: {}", e.getMessage());
        }
    }
}
