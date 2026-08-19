package ar.scraper.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the {@code usuario} aggregate and its role grants.
 *
 * <p>Two shapes here are deliberate and both exist to remove a branch from the
 * caller rather than to save a query:</p>
 *
 * <ul>
 *   <li>{@link #buscarActivaPorUsername} filters {@code activo = TRUE} in SQL.
 *       An empty result therefore means "no usable account", collapsing
 *       "unknown user" and "disabled user" into one case. Login never has to
 *       remember the second check, and forgetting it would be a revoked account
 *       that still logs in.</li>
 *   <li>{@link #crear} is an insert-if-absent that reports whether it inserted,
 *       and <b>never overwrites an existing {@code password_hash}</b>. The
 *       bootstrap seeder runs on every boot; without this it would reset the
 *       admin password back to the environment value on each restart, silently
 *       undoing a password change.</li>
 * </ul>
 *
 * <p>Unlike the older repositories in this package, the methods here do not
 * swallow their exceptions. A favourite that fails to save is an annoyance; an
 * account operation that fails silently is an authentication decision made on
 * bad data, so failures propagate as {@link DatabaseException}.</p>
 */
public class UsuarioRepository {

    private static final Logger LOG = LoggerFactory.getLogger(UsuarioRepository.class);

    private final DataSource dataSource;

    public UsuarioRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** A row of {@code usuario}, without its roles. */
    public record Cuenta(UUID id,
                         String username,
                         String email,
                         String passwordHash,
                         boolean esServicio) {
    }

    /** Thrown instead of logging and returning a wrong answer. */
    public static class DatabaseException extends RuntimeException {
        DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Inserts the account if no row holds that {@code username}.
     *
     * @return {@code true} when this call created the row, {@code false} when it
     *         already existed — in which case nothing about it was modified.
     */
    public boolean crear(String username, String email, String passwordHash, boolean esServicio) {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO usuario (username, email, password_hash, es_servicio)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (username) DO NOTHING
                    """)) {
            ps.setString(1, username);
            ps.setString(2, email);
            ps.setString(3, passwordHash);
            ps.setBoolean(4, esServicio);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new DatabaseException("no se pudo crear la cuenta '" + username + "'", e);
        }
    }

    /** Empty for an unknown username AND for a disabled one — both are "unusable". */
    public Optional<Cuenta> buscarActivaPorUsername(String username) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    SELECT id, username, email, password_hash, es_servicio
                    FROM usuario
                    WHERE username = ? AND activo = TRUE
                    """)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
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
        } catch (Exception e) {
            throw new DatabaseException("no se pudo leer la cuenta '" + username + "'", e);
        }
    }

    public List<String> rolesDe(String username) {
        List<String> roles = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    SELECT r.nombre
                    FROM usuario u
                    JOIN usuario_rol ur ON ur.usuario_id = u.id
                    JOIN rol r          ON r.id = ur.rol_id
                    WHERE u.username = ?
                    ORDER BY r.nombre
                    """)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    roles.add(rs.getString(1));
                }
            }
            return roles;
        } catch (Exception e) {
            throw new DatabaseException("no se pudieron leer los roles de '" + username + "'", e);
        }
    }

    /** Idempotent: granting a role the account already holds changes nothing. */
    public void asignarRol(String username, String rol) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO usuario_rol (usuario_id, rol_id)
                    SELECT u.id, r.id
                    FROM usuario u, rol r
                    WHERE u.username = ? AND r.nombre = ?
                    ON CONFLICT DO NOTHING
                    """)) {
            ps.setString(1, username);
            ps.setString(2, rol);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new DatabaseException("no se pudo asignar el rol " + rol + " a '" + username + "'", e);
        }
    }

    /**
     * Revocation switch. Deliberately not a DELETE: the row keeps the audit
     * trail and the referential integrity of every grant and token that points
     * at it, and re-enabling is a one-column update rather than a re-creation.
     */
    public void desactivar(String username) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE usuario SET activo = FALSE WHERE username = ?")) {
            ps.setString(1, username);
            int filas = ps.executeUpdate();
            if (filas == 0) {
                LOG.warn("[DB] desactivar: no existe la cuenta '{}'", username);
            }
        } catch (Exception e) {
            throw new DatabaseException("no se pudo desactivar la cuenta '" + username + "'", e);
        }
    }
}
