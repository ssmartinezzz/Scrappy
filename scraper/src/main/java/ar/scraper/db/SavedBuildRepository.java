package ar.scraper.db;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A saved build is a header row owned by a user plus its items. Every operation keeps the
 * sentinel contract: log and return {@code -1}, {@code false} or what was read so far.
 */
abstract class SavedBuildRepository {

    private final Logger log = LoggerFactory.getLogger(getClass());

    protected final JdbcTemplate jdbc;
    private final String tabla;
    private final String entidad;

    SavedBuildRepository(DataSource dataSource, String tabla, String entidad) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tabla = tabla;
        this.entidad = entidad;
    }

    /** Callers are {@code @Transactional}: a failure marks the rollback, never a half-saved build. */
    protected final int guardar(PreparedStatementCreator cabecera, IntConsumer items) {
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(cabecera, keys);
            Number id = keys.getKey();
            if (id == null) {
                Sql.marcarRollback();
                return -1;
            }
            items.accept(id.intValue());
            return id.intValue();
        } catch (Exception e) {
            log.warn("[DB] Error guardando {}, rollback: {}", entidad, e.getMessage());
            Sql.marcarRollback();
            return -1;
        }
    }

    protected final List<Map<String, Object>> listar(UUID usuarioId, String sql,
            RowMapper<Map<String, Object>> fila, Consumer<List<Map<String, Object>>> cargarItems) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            result.addAll(jdbc.query(sql, fila, usuarioId));
            cargarItems.accept(result);
        } catch (Exception e) {
            log.warn("[DB] Error obteniendo {}: {}", tabla, e.getMessage());
        }
        return result;
    }

    protected final boolean eliminar(UUID usuarioId, int id) {
        try {
            return jdbc.update("DELETE FROM " + tabla + " WHERE usuario_id=? AND id=?", usuarioId, id) > 0;
        } catch (Exception e) {
            log.warn("[DB] Error eliminando {} guardado {}: {}", entidad, id, e.getMessage());
            return false;
        }
    }

    protected final boolean renombrar(UUID usuarioId, int id, String nombre) {
        if (StringUtils.isBlank(nombre)) return false;
        try {
            return jdbc.update("UPDATE " + tabla + " SET nombre=? WHERE usuario_id=? AND id=?",
                    nombre.trim(), usuarioId, id) > 0;
        } catch (Exception e) {
            log.warn("[DB] Error renombrando {} {}: {}", entidad, id, e.getMessage());
            return false;
        }
    }
}
