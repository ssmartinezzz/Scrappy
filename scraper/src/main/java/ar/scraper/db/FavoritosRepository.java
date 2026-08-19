package ar.scraper.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Persistence for the {@code favoritos} aggregate.
 *
 * <p>Extracted verbatim from {@link DatabaseService} (backlog A3).</p>
 *
 * <p><b>Every method takes {@code usuarioId} first, and there is no unscoped
 * variant of any of them.</b> That absence is the design: a role-branching read
 * (<code>isAdmin ? selectAll() : selectMine()</code>) is where a leak eventually
 * appears — a third caller forgets the branch, or a refactor inverts the
 * condition, and it fails silently because the ADMIN path <i>looks</i> like it
 * works. A method that does not exist cannot be called by mistake, and the
 * compiler enforces that rather than a reviewer. ADMIN and VIEWER run
 * byte-identical SQL with a different bound parameter; that is the entire
 * implementation of "an ADMIN sees only their own personal data".</p>
 *
 * <p><b>The upsert conflicts on {@code uq_fav_owner_url} by name.</b> Rows now
 * carry an owner, so the target is the {@code (usuario_id, url)} constraint
 * rather than the partial index that covered the ownerless window. Naming the
 * constraint instead of inferring from a column list means a future key change
 * fails loudly here rather than silently matching the wrong index — and a silent
 * failure would read as "no favourites saved", because the methods below log and
 * swallow.</p>
 */
class FavoritosRepository {

    private static final Logger LOG = LoggerFactory.getLogger(FavoritosRepository.class);

    private final DataSource dataSource;

    FavoritosRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    void guardarFavorito(UUID usuarioId, String url, String sitio, String nombre) {
        Objects.requireNonNull(usuarioId, "usuarioId must not be null");
        Objects.requireNonNull(url, "url must not be null");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO favoritos (usuario_id, url, sitio, nombre, added_at, last_checked_at)
                    VALUES (?, ?, ?, ?, ?, NULL)
                    ON CONFLICT ON CONSTRAINT uq_fav_owner_url
                    DO UPDATE SET sitio=excluded.sitio, nombre=excluded.nombre
                    """)) {
            ps.setObject(1, usuarioId);
            ps.setString(2, url);
            ps.setString(3, sitio);
            ps.setString(4, nombre);
            ps.setObject(5, Timestamps.now());
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando favorito: {}", e.getMessage());
        }
    }

    void eliminarFavorito(UUID usuarioId, String url) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "DELETE FROM favoritos WHERE usuario_id=? AND url=?")) {
            ps.setObject(1, usuarioId);
            ps.setString(2, url);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando favorito: {}", e.getMessage());
        }
    }

    List<Map<String, String>> listarFavoritos(UUID usuarioId) {
        List<Map<String, String>> result = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT url, sitio, nombre, added_at, last_checked_at " +
                "FROM favoritos WHERE usuario_id=? ORDER BY added_at DESC")) {
            ps.setObject(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("url",            rs.getString(1));
                row.put("sitio",          rs.getString(2));
                row.put("nombre",         rs.getString(3));
                row.put("added_at",       Timestamps.iso(rs, "added_at"));
                row.put("last_checked_at", Timestamps.iso(rs, "last_checked_at"));
                result.add(row);
                }
            }
        } catch (Exception e) {
            LOG.warn("[DB] Error listando favoritos: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Stamps {@code last_checked_at}.
     *
     * <p>Scoped like everything else even though it writes no personal content:
     * an unscoped variant sitting here is a method somebody will later reach for
     * when they want "all of them", which is exactly the door this class keeps
     * shut.</p>
     */
    void tocarFavorito(UUID usuarioId, String url) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE favoritos SET last_checked_at=? WHERE usuario_id=? AND url=?")) {
            ps.setObject(1, Timestamps.now());
            ps.setObject(2, usuarioId);
            ps.setString(3, url);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando last_checked_at: {}", e.getMessage());
        }
    }
}
