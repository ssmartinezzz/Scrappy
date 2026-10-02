package ar.scraper.db;

import ar.scraper.favoritos.FavoritosPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A method that does not exist cannot be called by mistake, and the compiler enforces that rather
 * than a reviewer.
 */
@Repository
class FavoritosRepository implements FavoritosPort {

    private static final Logger LOG = LoggerFactory.getLogger(FavoritosRepository.class);

    private final JdbcTemplate jdbc;

    FavoritosRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void guardarFavorito(UUID usuarioId, String url, String sitio, String nombre) {
        Objects.requireNonNull(usuarioId, "usuarioId must not be null");
        Objects.requireNonNull(url, "url must not be null");
        try {
            jdbc.update("""
                    INSERT INTO favoritos (usuario_id, url, sitio, nombre, added_at, last_checked_at)
                    VALUES (?, ?, ?, ?, ?, NULL)
                    ON CONFLICT ON CONSTRAINT uq_fav_owner_url
                    DO UPDATE SET sitio=excluded.sitio, nombre=excluded.nombre
                    """, ps -> {
                ps.setObject(1, usuarioId);
                ps.setString(2, url);
                ps.setString(3, sitio);
                ps.setString(4, nombre);
                ps.setObject(5, Timestamps.now());
            });
        } catch (Exception e) {
            LOG.warn("[DB] Error guardando favorito: {}", e.getMessage());
        }
    }

    @Override
    public void eliminarFavorito(UUID usuarioId, String url) {
        try {
            jdbc.update("DELETE FROM favoritos WHERE usuario_id=? AND url=?", ps -> {
                ps.setObject(1, usuarioId);
                ps.setString(2, url);
            });
        } catch (Exception e) {
            LOG.warn("[DB] Error eliminando favorito: {}", e.getMessage());
        }
    }

    @Override
    public List<Map<String, String>> listarFavoritos(UUID usuarioId) {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT url, sitio, nombre, added_at, last_checked_at " +
                    "FROM favoritos WHERE usuario_id=? ORDER BY added_at DESC",
                    ps -> ps.setObject(1, usuarioId),
                    rs -> {
                        Map<String, String> row = new LinkedHashMap<>();
                        row.put("url",            rs.getString(1));
                        row.put("sitio",          rs.getString(2));
                        row.put("nombre",         rs.getString(3));
                        row.put("added_at",       Timestamps.iso(rs, "added_at"));
                        row.put("last_checked_at", Timestamps.iso(rs, "last_checked_at"));
                        result.add(row);
                    });
        } catch (Exception e) {
            LOG.warn("[DB] Error listando favoritos: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public void tocarFavorito(UUID usuarioId, String url) {
        try {
            jdbc.update("UPDATE favoritos SET last_checked_at=? WHERE usuario_id=? AND url=?", ps -> {
                ps.setObject(1, Timestamps.now());
                ps.setObject(2, usuarioId);
                ps.setString(3, url);
            });
        } catch (Exception e) {
            LOG.warn("[DB] Error actualizando last_checked_at: {}", e.getMessage());
        }
    }
}
