package ar.scraper.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Two shapes here are deliberate and both exist to remove a branch from the caller rather than to
 * save a query: Login never has to remember the second check, and forgetting it would be a revoked
 * account that still logs in.
 */
@Repository
public class UsuarioRepository {

    private static final Logger LOG = LoggerFactory.getLogger(UsuarioRepository.class);

    private final JdbcTemplate jdbc;

    public UsuarioRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** A row of {@code usuario}, without its roles. */
    public record Cuenta(UUID id,
                         String username,
                         String email,
                         String passwordHash,
                         boolean esServicio) {
    }

    /**
     * Thrown instead of logging and returning a wrong answer. Public constructor because the
     * account-adjacent services outside this package — the reset flow, the session store — need to
     * raise the same kind of failure.
     */
    public static class DatabaseException extends RuntimeException {
        public DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public boolean crear(String username, String email, String passwordHash, boolean esServicio) {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        try {
            return jdbc.update("""
                    INSERT INTO usuario (username, email, password_hash, es_servicio)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (username) DO NOTHING
                    """, ps -> {
                ps.setString(1, username);
                ps.setString(2, email);
                ps.setString(3, passwordHash);
                ps.setBoolean(4, esServicio);
            }) == 1;
        } catch (Exception e) {
            throw new DatabaseException("no se pudo crear la cuenta '" + username + "'", e);
        }
    }

    public Optional<Cuenta> buscarActivaPorUsername(String username) {
        try {
            return jdbc.query("""
                    SELECT id, username, email, password_hash, es_servicio
                    FROM usuario
                    WHERE username = ? AND activo = TRUE
                    """, ps -> ps.setString(1, username), UsuarioRepository::cuentaOVacia);
        } catch (Exception e) {
            throw new DatabaseException("no se pudo leer la cuenta '" + username + "'", e);
        }
    }

    private static Optional<Cuenta> cuentaOVacia(ResultSet rs) throws SQLException {
        if (!rs.next()) {
            return Optional.empty();
        }
        return Optional.of(new Cuenta(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getBoolean(5)));
    }

    public List<String> rolesDe(String username) {
        List<String> roles = new ArrayList<>();
        try {
            jdbc.query("""
                    SELECT r.nombre
                    FROM usuario u
                    JOIN usuario_rol ur ON ur.usuario_id = u.id
                    JOIN rol r          ON r.id = ur.rol_id
                    WHERE u.username = ?
                    ORDER BY r.nombre
                    """, ps -> ps.setString(1, username), rs -> {
                roles.add(rs.getString(1));
            });
            return roles;
        } catch (Exception e) {
            throw new DatabaseException("no se pudieron leer los roles de '" + username + "'", e);
        }
    }

    /** Idempotent: granting a role the account already holds changes nothing. */
    public void asignarRol(String username, String rol) {
        try {
            jdbc.update("""
                    INSERT INTO usuario_rol (usuario_id, rol_id)
                    SELECT u.id, r.id
                    FROM usuario u, rol r
                    WHERE u.username = ? AND r.nombre = ?
                    ON CONFLICT DO NOTHING
                    """, ps -> {
                ps.setString(1, username);
                ps.setString(2, rol);
            });
        } catch (Exception e) {
            throw new DatabaseException("no se pudo asignar el rol " + rol + " a '" + username + "'", e);
        }
    }

    /**
     * Deliberately not a DELETE: the row keeps the audit trail and the referential integrity of
     * every grant and token that points at it, and re-enabling is a one-column update rather than a
     * re-creation.
     */
    public void desactivar(String username) {
        try {
            int filas = jdbc.update("UPDATE usuario SET activo = FALSE WHERE username = ?", username);
            if (filas == 0) {
                LOG.warn("[DB] desactivar: no existe la cuenta '{}'", username);
            }
        } catch (Exception e) {
            throw new DatabaseException("no se pudo desactivar la cuenta '" + username + "'", e);
        }
    }

    /** Reset looks accounts up by address; login never does. */
    public Optional<Cuenta> buscarActivaPorEmail(String email) {
        try {
            return jdbc.query("""
                    SELECT id, username, email, password_hash, es_servicio
                    FROM usuario
                    WHERE email = ? AND activo = TRUE
                    """, ps -> ps.setString(1, email == null ? null : email.trim().toLowerCase()),
                    UsuarioRepository::cuentaOVacia);
        } catch (Exception e) {
            throw new DatabaseException("no se pudo buscar la cuenta por email", e);
        }
    }

    /** Joins the reset transaction when called inside one. The stamp is not bookkeeping. */
    public boolean cambiarPassword(UUID usuarioId, String passwordHash, java.time.Instant cuando) {
        try {
            return jdbc.update("UPDATE usuario SET password_hash = ?, password_changed_at = ? WHERE id = ?", ps -> {
                ps.setString(1, passwordHash);
                ps.setTimestamp(2, java.sql.Timestamp.from(cuando));
                ps.setObject(3, usuarioId);
            }) == 1;
        } catch (Exception e) {
            throw new DatabaseException("no se pudo cambiar la password", e);
        }
    }

    /**
     * Role and {@code password_changed_at} come back together because the filter needs both on
     * every single request, and two round-trips for one decision is the kind of cost that later
     * gets "optimised" into a cache — which is exactly what must not happen here, since a missed
     * eviction would be a privilege escalation nobody sees.
     */
    public Optional<Autorizacion> autorizacionDe(UUID usuarioId) {
        try {
            return jdbc.query("""
                    SELECT u.username, u.password_changed_at, r.nombre
                    FROM usuario u
                    JOIN usuario_rol ur ON ur.usuario_id = u.id
                    JOIN rol r          ON r.id = ur.rol_id
                    WHERE u.id = ? AND u.activo = TRUE
                    ORDER BY r.nombre
                    """, ps -> ps.setObject(1, usuarioId), rs -> {
                String username = null;
                java.time.Instant cambiada = null;
                List<String> roles = new ArrayList<>();
                while (rs.next()) {
                    username = rs.getString(1);
                    java.sql.Timestamp ts = rs.getTimestamp(2);
                    cambiada = ts == null ? null : ts.toInstant();
                    roles.add(rs.getString(3));
                }
                if (roles.isEmpty()) {
                    return Optional.<Autorizacion>empty();
                }
                return Optional.of(new Autorizacion(username, roles, cambiada));
            });
        } catch (Exception e) {
            throw new DatabaseException("no se pudo leer la autorización del usuario", e);
        }
    }

    public record Autorizacion(String username, List<String> roles, java.time.Instant passwordChangedAt) {}

    /** Never carries the hash. */
    public record Ficha(UUID id, String username, String email, boolean activo,
                        boolean esServicio, List<String> roles) {}


    /**
     * Disabled ones are included on purpose: an admin looking for the person they locked out last
     * week needs to find them in order to let them back in.
     */
    public List<Ficha> listar() {
        Map<UUID, Ficha> porId = new java.util.LinkedHashMap<>();
        try {
            jdbc.query("""
                    SELECT u.id, u.username, u.email, u.activo, u.es_servicio, r.nombre
                    FROM usuario u
                    LEFT JOIN usuario_rol ur ON ur.usuario_id = u.id
                    LEFT JOIN rol r          ON r.id = ur.rol_id
                    ORDER BY u.username, r.nombre
                    """, rs -> {
                UUID id = rs.getObject(1, UUID.class);
                Ficha previa = porId.get(id);
                List<String> roles = previa == null ? new ArrayList<>() : new ArrayList<>(previa.roles());
                String rol = rs.getString(6);
                if (rol != null) {
                    roles.add(rol);
                }
                porId.put(id, new Ficha(id, rs.getString(2), rs.getString(3),
                        rs.getBoolean(4), rs.getBoolean(5), roles));
            });
            return List.copyOf(porId.values());
        } catch (Exception e) {
            throw new DatabaseException("no se pudieron listar las cuentas", e);
        }
    }

    /** The closed vocabulary, read from the table rather than hardcoded a second time. */
    public List<String> rolesValidos() {
        List<String> roles = new ArrayList<>();
        try {
            jdbc.query("SELECT nombre FROM rol ORDER BY nombre", rs -> {
                roles.add(rs.getString(1));
            });
            return roles;
        } catch (Exception e) {
            throw new DatabaseException("no se pudo leer el vocabulario de roles", e);
        }
    }

    /**
     * Atomic because the halves are useless apart: a user with no role cannot authorize anything
     * (the per-request lookup returns empty and reads as "disabled"), and a grant with no user is
     * impossible.
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<UUID> crearConRol(String username, String email, String passwordHash, String rol) {
        if (!rolesValidos().contains(rol)) {
            throw new IllegalArgumentException("rol inválido: " + rol);
        }
        if (existe(username)) {
            return Optional.empty();
        }
        return Optional.of(sembrarCuenta(username, email, passwordHash, false, rol));
    }

    /**
     * A replacement rather than an addition: the matrix has two roles and ADMIN strictly contains
     * VIEWER's reach, so "add VIEWER to an ADMIN" is never a meaningful request, while accidentally
     * leaving the old grant in place would be a demotion that did not demote.
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean reemplazarRol(String username, String rol) {
        if (!rolesValidos().contains(rol)) {
            throw new IllegalArgumentException("rol inválido: " + rol);
        }
        try {
            jdbc.update("""
                    DELETE FROM usuario_rol
                     WHERE usuario_id = (SELECT id FROM usuario WHERE username = ?)
                    """, username);
            return jdbc.update("""
                    INSERT INTO usuario_rol (usuario_id, rol_id)
                    SELECT u.id, r.id FROM usuario u, rol r
                    WHERE u.username = ? AND r.nombre = ?
                    """, ps -> {
                ps.setString(1, username);
                ps.setString(2, rol);
            }) == 1;
        } catch (Exception e) {
            throw new DatabaseException("no se pudo reemplazar el rol de '" + username + "'", e);
        }
    }

    public int adminsActivos() {
        try {
            return jdbc.query("""
                    SELECT count(DISTINCT u.id)
                    FROM usuario u
                    JOIN usuario_rol ur ON ur.usuario_id = u.id
                    JOIN rol r          ON r.id = ur.rol_id
                    WHERE u.activo = TRUE AND r.nombre = 'ADMIN'
                    """, rs -> rs.next() ? rs.getInt(1) : 0);
        } catch (Exception e) {
            throw new DatabaseException("no se pudieron contar los administradores activos", e);
        }
    }

    public boolean esAdminActivo(String username) {
        return rolesDe(username).contains("ADMIN")
                && buscarActivaPorUsername(username).isPresent();
    }

    public boolean reactivar(String username) {
        try {
            return jdbc.update("UPDATE usuario SET activo = TRUE WHERE username = ?", username) == 1;
        } catch (Exception e) {
            throw new DatabaseException("no se pudo reactivar la cuenta '" + username + "'", e);
        }
    }

    public boolean existe(String username) {
        try {
            return jdbc.query("SELECT 1 FROM usuario WHERE username = ?",
                    ps -> ps.setString(1, username), ResultSet::next);
        } catch (Exception e) {
            throw new DatabaseException("no se pudo verificar la cuenta '" + username + "'", e);
        }
    }

    private static final List<String> TABLAS_CON_DUENO =
            List.of("favoritos", "saved_outfits", "outfit_feedback_item", "categoria_dismiss");

    /**
     * Seeds the bootstrap admin and the service account, then hands every ownerless row to the
     * admin, all in one transaction.
     */
    @Transactional(rollbackFor = Exception.class)
    public int sembrarAdministracion(String adminUsername, String hashAdmin,
                                     String servicioUsername, String hashServicio, String rol) {
        UUID adminId = sembrarCuenta(adminUsername, null, hashAdmin, false, rol);
        sembrarCuenta(servicioUsername, null, hashServicio, true, rol);
        return adoptarFilasSinDueno(adminId);
    }

    private UUID sembrarCuenta(String username, String email, String passwordHash,
                               boolean esServicio, String rol) {
        try {
            jdbc.update("""
                    INSERT INTO usuario (username, email, password_hash, es_servicio)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (username) DO NOTHING
                    """, ps -> {
                ps.setString(1, username);
                ps.setString(2, email);
                ps.setString(3, passwordHash);
                ps.setBoolean(4, esServicio);
            });
        } catch (Exception e) {
            throw new DatabaseException("no se pudo sembrar la cuenta '" + username + "'", e);
        }

        try {
            jdbc.update("""
                    INSERT INTO usuario_rol (usuario_id, rol_id)
                    SELECT u.id, r.id
                    FROM usuario u, rol r
                    WHERE u.username = ? AND r.nombre = ?
                    ON CONFLICT DO NOTHING
                    """, ps -> {
                ps.setString(1, username);
                ps.setString(2, rol);
            });
        } catch (Exception e) {
            throw new DatabaseException("no se pudo asignar el rol " + rol + " a '" + username + "'", e);
        }

        UUID id;
        try {
            id = jdbc.query("SELECT id FROM usuario WHERE username = ?",
                    ps -> ps.setString(1, username),
                    rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
        } catch (Exception e) {
            throw new DatabaseException("no se pudo leer el id de '" + username + "'", e);
        }
        if (id == null) {
            throw new DatabaseException("la cuenta '" + username + "' no existe después de sembrarla", null);
        }
        return id;
    }

    /**
     * Scoped to {@code usuario_id IS NULL}, which is what makes it both idempotent (a second run
     * matches nothing) and safe to run while other accounts already own rows — it claims the
     * unclaimed, never the owned.
     */
    private int adoptarFilasSinDueno(UUID duenoId) {
        int adoptadas = 0;
        for (String tabla : TABLAS_CON_DUENO) {
            try {
                adoptadas += jdbc.update("UPDATE " + tabla + " SET usuario_id = ? WHERE usuario_id IS NULL",
                        ps -> ps.setObject(1, duenoId));
            } catch (Exception e) {
                throw new DatabaseException("no se pudieron adoptar las filas de " + tabla, e);
            }
        }
        return adoptadas;
    }
}
