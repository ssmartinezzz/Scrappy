package ar.scraper.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

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
 * <p><b>A Spring bean, unlike its siblings in this package.</b> The others are
 * package-private and constructed inside {@link DatabaseService}, which then
 * delegates to them. This one is injected directly into {@code AuthEndpoints}
 * and {@code AdminSeeder}, because authentication has no reason to reach
 * through a service that also owns scraping, the catalogue and the ML output.
 * There is deliberately no second copy behind {@code DatabaseService}: two
 * instances of the same repository is how a future stateful field ends up
 * disagreeing with itself.</p>
 *
 * <p>Unlike the older repositories in this package, the methods here do not
 * swallow their exceptions. A favourite that fails to save is an annoyance; an
 * account operation that fails silently is an authentication decision made on
 * bad data, so failures propagate as {@link DatabaseException}.</p>
 */
@Repository
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

    /**
     * Thrown instead of logging and returning a wrong answer.
     *
     * <p>Public constructor because the account-adjacent services outside this
     * package — the reset flow, the session store — need to raise the same kind
     * of failure. Their alternative is inventing a parallel exception for the
     * same condition, which only makes the callers catch two things.</p>
     */
    public static class DatabaseException extends RuntimeException {
        public DatabaseException(String message, Throwable cause) {
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

    /** Reset looks accounts up by address; login never does. */
    public Optional<Cuenta> buscarActivaPorEmail(String email) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    SELECT id, username, email, password_hash, es_servicio
                    FROM usuario
                    WHERE email = ? AND activo = TRUE
                    """)) {
            ps.setString(1, email == null ? null : email.trim().toLowerCase());
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
            throw new DatabaseException("no se pudo buscar la cuenta por email", e);
        }
    }

    /**
     * Sets a new hash and stamps {@code password_changed_at}, on the caller's
     * connection so it can join the reset transaction.
     *
     * <p>The stamp is not bookkeeping. Access tokens already issued stay
     * cryptographically valid for up to fifteen minutes after a reset, and
     * comparing a token's {@code iat} against this column is what closes that
     * window — at no extra query, because the per-request authorization lookup
     * reads it anyway.</p>
     */
    public boolean cambiarPassword(Connection c, UUID usuarioId, String passwordHash, java.time.Instant cuando) {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE usuario SET password_hash = ?, password_changed_at = ? WHERE id = ?")) {
            ps.setString(1, passwordHash);
            ps.setTimestamp(2, java.sql.Timestamp.from(cuando));
            ps.setObject(3, usuarioId);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new DatabaseException("no se pudo cambiar la password", e);
        }
    }

    /**
     * Everything authorization needs about a subject, in <b>one</b> query.
     *
     * <p>Role and {@code password_changed_at} come back together because the
     * filter needs both on every single request, and two round-trips for one
     * decision is the kind of cost that later gets "optimised" into a cache —
     * which is exactly what must not happen here, since a missed eviction would
     * be a privilege escalation nobody sees.</p>
     *
     * <p>Filters {@code activo = TRUE}, so an empty result covers unknown,
     * disabled and role-less alike.</p>
     */
    public Optional<Autorizacion> autorizacionDe(UUID usuarioId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    SELECT u.username, u.password_changed_at, r.nombre
                    FROM usuario u
                    JOIN usuario_rol ur ON ur.usuario_id = u.id
                    JOIN rol r          ON r.id = ur.rol_id
                    WHERE u.id = ? AND u.activo = TRUE
                    ORDER BY r.nombre
                    """)) {
            ps.setObject(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
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
                    return Optional.empty();
                }
                return Optional.of(new Autorizacion(username, roles, cambiada));
            }
        } catch (Exception e) {
            throw new DatabaseException("no se pudo leer la autorización del usuario", e);
        }
    }

    /** What the per-request authorization check reads. */
    public record Autorizacion(String username, List<String> roles, java.time.Instant passwordChangedAt) {}

    // ─── Administración de cuentas (slice 9) ────────────────────────────────

    /** A user as the administration surface shows them. Never carries the hash. */
    public record Ficha(UUID id, String username, String email, boolean activo,
                        boolean esServicio, List<String> roles) {}

    /**
     * Every account, active and disabled alike.
     *
     * <p>Disabled ones are included on purpose: an admin looking for the person
     * they locked out last week needs to find them in order to let them back in.
     * A list that silently omitted them would make deactivation look like
     * deletion, which is exactly the confusion {@link #desactivar} avoids.</p>
     *
     * <p>{@code password_hash} is not selected. Nothing in this surface needs it,
     * and a field that is never fetched cannot be leaked by a future serializer
     * that helpfully includes every column.</p>
     */
    public List<Ficha> listar() {
        Map<UUID, Ficha> porId = new java.util.LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    SELECT u.id, u.username, u.email, u.activo, u.es_servicio, r.nombre
                    FROM usuario u
                    LEFT JOIN usuario_rol ur ON ur.usuario_id = u.id
                    LEFT JOIN rol r          ON r.id = ur.rol_id
                    ORDER BY u.username, r.nombre
                    """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UUID id = rs.getObject(1, UUID.class);
                Ficha previa = porId.get(id);
                List<String> roles = previa == null ? new ArrayList<>() : new ArrayList<>(previa.roles());
                String rol = rs.getString(6);
                if (rol != null) {
                    roles.add(rol);
                }
                porId.put(id, new Ficha(id, rs.getString(2), rs.getString(3),
                        rs.getBoolean(4), rs.getBoolean(5), roles));
            }
            return List.copyOf(porId.values());
        } catch (Exception e) {
            throw new DatabaseException("no se pudieron listar las cuentas", e);
        }
    }

    /** The closed vocabulary, read from the table rather than hardcoded a second time. */
    public List<String> rolesValidos() {
        List<String> roles = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT nombre FROM rol ORDER BY nombre");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                roles.add(rs.getString(1));
            }
            return roles;
        } catch (Exception e) {
            throw new DatabaseException("no se pudo leer el vocabulario de roles", e);
        }
    }

    /**
     * Creates the account and grants its role in ONE transaction.
     *
     * <p>Atomic because the halves are useless apart: a user with no role cannot
     * authorize anything (the per-request lookup returns empty and reads as
     * "disabled"), and a grant with no user is impossible. Creating one without
     * the other would leave an account that looks present in a listing and
     * cannot log in, with no indication why.</p>
     *
     * @return the new account's id, or empty when the username is taken.
     * @throws IllegalArgumentException for a role outside the closed vocabulary —
     *         thrown, not silently ignored: {@link #asignarRol} matches on
     *         {@code r.nombre}, so an invalid role would grant nothing and the
     *         account would be born unusable.
     */
    public Optional<UUID> crearConRol(String username, String email, String passwordHash, String rol) {
        if (!rolesValidos().contains(rol)) {
            throw new IllegalArgumentException("rol inválido: " + rol);
        }
        return enTransaccion(tx -> {
            if (!tx.existeUsername(username)) {
                return Optional.of(tx.sembrarCuenta(username, email, passwordHash, false, rol));
            }
            return Optional.empty();
        });
    }

    /**
     * Replaces the account's roles with exactly {@code rol}.
     *
     * <p>A replacement rather than an addition: the matrix has two roles and ADMIN
     * strictly contains VIEWER's reach, so "add VIEWER to an ADMIN" is never a
     * meaningful request, while accidentally leaving the old grant in place would
     * be a demotion that did not demote.</p>
     */
    public boolean reemplazarRol(String username, String rol) {
        if (!rolesValidos().contains(rol)) {
            throw new IllegalArgumentException("rol inválido: " + rol);
        }
        return enTransaccion(tx -> tx.reemplazarRol(username, rol));
    }

    /** Counts active ADMINs. Used to refuse removing the last one. */
    public int adminsActivos() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    SELECT count(DISTINCT u.id)
                    FROM usuario u
                    JOIN usuario_rol ur ON ur.usuario_id = u.id
                    JOIN rol r          ON r.id = ur.rol_id
                    WHERE u.activo = TRUE AND r.nombre = 'ADMIN'
                    """);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            throw new DatabaseException("no se pudieron contar los administradores activos", e);
        }
    }

    public boolean esAdminActivo(String username) {
        return rolesDe(username).contains("ADMIN")
                && buscarActivaPorUsername(username).isPresent();
    }

    /** Puts a deactivated account back in service. */
    public boolean reactivar(String username) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE usuario SET activo = TRUE WHERE username = ?")) {
            ps.setString(1, username);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new DatabaseException("no se pudo reactivar la cuenta '" + username + "'", e);
        }
    }

    public boolean existe(String username) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM usuario WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            throw new DatabaseException("no se pudo verificar la cuenta '" + username + "'", e);
        }
    }

    // ─── Unidad de trabajo transaccional ─────────────────────────────────────

    /**
     * Runs {@code trabajo} against one connection with autocommit off,
     * committing on return and rolling back on any throw.
     *
     * <p>It exists because bootstrap seeding and ownership adoption have to be
     * one atomic step. Adopting rows into an admin account that a later failure
     * rolls back would leave every personal row pointing at a user id that does
     * not exist — a dangling owner is worse than no owner, because the rows
     * become unreachable rather than merely unclaimed.</p>
     */
    public <T> T enTransaccion(Function<Tx, T> trabajo) {
        try (Connection c = dataSource.getConnection()) {
            boolean autocommitPrevio = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T resultado = trabajo.apply(new Tx(c));
                c.commit();
                return resultado;
            } catch (RuntimeException | Error e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autocommitPrevio);
            }
        } catch (java.sql.SQLException e) {
            throw new DatabaseException("falló la transacción de cuentas", e);
        }
    }

    /** The four tables a person owns rows in. {@code saved_outfit_item} inherits through its parent. */
    private static final List<String> TABLAS_CON_DUENO =
            List.of("favoritos", "saved_outfits", "outfit_feedback_item", "categoria_dismiss");

    /** Connection-scoped operations. Only reachable from {@link #enTransaccion}. */
    public final class Tx {

        private final Connection c;

        private Tx(Connection c) {
            this.c = c;
        }

        /**
         * Insert-if-absent plus the role grant, both idempotent.
         *
         * @return the account's id, whether this call created it or found it.
         *         An existing {@code password_hash} is never touched — see
         *         {@link UsuarioRepository#crear}.
         */
        public UUID sembrarCuenta(String username, String email, String passwordHash,
                                  boolean esServicio, String rol) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO usuario (username, email, password_hash, es_servicio)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (username) DO NOTHING
                    """)) {
                ps.setString(1, username);
                ps.setString(2, email);
                ps.setString(3, passwordHash);
                ps.setBoolean(4, esServicio);
                ps.executeUpdate();
            } catch (Exception e) {
                throw new DatabaseException("no se pudo sembrar la cuenta '" + username + "'", e);
            }

            try (PreparedStatement ps = c.prepareStatement("""
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

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM usuario WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new DatabaseException(
                                "la cuenta '" + username + "' no existe después de sembrarla", null);
                    }
                    return rs.getObject(1, UUID.class);
                }
            } catch (DatabaseException e) {
                throw e;
            } catch (Exception e) {
                throw new DatabaseException("no se pudo leer el id de '" + username + "'", e);
            }
        }

        /** Inside the transaction, so create-if-absent cannot race with itself. */
        public boolean existeUsername(String username) {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM usuario WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (Exception e) {
                throw new DatabaseException("no se pudo verificar '" + username + "'", e);
            }
        }

        /** Drops every existing grant and leaves exactly one. */
        public boolean reemplazarRol(String username, String rol) {
            try (PreparedStatement ps = c.prepareStatement("""
                    DELETE FROM usuario_rol
                     WHERE usuario_id = (SELECT id FROM usuario WHERE username = ?)
                    """)) {
                ps.setString(1, username);
                ps.executeUpdate();
            } catch (Exception e) {
                throw new DatabaseException("no se pudo limpiar el rol de '" + username + "'", e);
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO usuario_rol (usuario_id, rol_id)
                    SELECT u.id, r.id FROM usuario u, rol r
                    WHERE u.username = ? AND r.nombre = ?
                    """)) {
                ps.setString(1, username);
                ps.setString(2, rol);
                return ps.executeUpdate() == 1;
            } catch (Exception e) {
                throw new DatabaseException("no se pudo asignar el rol a '" + username + "'", e);
            }
        }

        /**
         * Claims every ownerless row for {@code duenoId}.
         *
         * <p>Scoped to {@code usuario_id IS NULL}, which is what makes it both
         * idempotent (a second run matches nothing) and safe to run while other
         * accounts already own rows — it claims the unclaimed, never the owned.</p>
         *
         * @return how many rows were adopted, across all four tables.
         */
        public int adoptarFilasSinDueno(UUID duenoId) {
            int adoptadas = 0;
            for (String tabla : TABLAS_CON_DUENO) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + tabla + " SET usuario_id = ? WHERE usuario_id IS NULL")) {
                    ps.setObject(1, duenoId);
                    adoptadas += ps.executeUpdate();
                } catch (Exception e) {
                    throw new DatabaseException("no se pudieron adoptar las filas de " + tabla, e);
                }
            }
            return adoptadas;
        }
    }
}
