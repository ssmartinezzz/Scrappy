package ar.scraper.db;

import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@code password_reset_token}.
 *
 * <p><b>Consuming a token is one statement, and that is the whole design.</b>
 * The obvious shape — read the row, check it is unused and unexpired, then
 * update it — has a window between the check and the update in which a second
 * request can pass the same check. Two people (or the same person clicking
 * twice, or an attacker racing a victim) would then both succeed with a token
 * that is documented as single-use. Folding the check into the {@code WHERE}
 * clause and letting Postgres report how many rows it touched removes the
 * window entirely: exactly one caller gets a row back, always.</p>
 *
 * <p>The column is {@code token_hash} and it holds a SHA-256 digest. Same
 * reasoning as {@link RefreshTokenRepository}: the value is high-entropy random,
 * so there is no dictionary attack to slow down — what hashing buys is that a
 * database dump is not a stack of working reset links.</p>
 */
@Repository
public class PasswordResetRepository {

    private final DataSource dataSource;

    public PasswordResetRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Same digest as the refresh tokens — see that class for why SHA-256 and not Argon2id. */
    public static String hash(String rawToken) {
        return RefreshTokenRepository.hash(rawToken);
    }

    public void crear(UUID usuarioId, String rawToken, Instant expiraEn) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO password_reset_token (token_hash, usuario_id, expires_at)
                    VALUES (?, ?, ?)
                    """)) {
            ps.setString(1, hash(rawToken));
            ps.setObject(2, usuarioId);
            ps.setTimestamp(3, Timestamp.from(expiraEn));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudo crear el token de reseteo", e);
        }
    }

    /**
     * Atomically marks the token consumed and reports whose it was.
     *
     * <p>Runs on the caller's connection so it can join the same transaction as
     * the password change — a consumed token whose password change then rolled
     * back would be a reset link burnt for nothing, and the user would be told
     * to request another one for a reason nobody could explain.</p>
     *
     * @return the owner's id, or empty when the token is unknown, already
     *         consumed, or expired. The three are not distinguished: a caller
     *         holding a bad token has no use for knowing which kind of bad.
     */
    public Optional<UUID> consumir(Connection c, String rawToken, Instant ahora) {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE password_reset_token
                   SET consumed_at = ?
                 WHERE token_hash = ?
                   AND consumed_at IS NULL
                   AND expires_at > ?
             RETURNING usuario_id
                """)) {
            ps.setTimestamp(1, Timestamp.from(ahora));
            ps.setString(2, hash(rawToken));
            ps.setTimestamp(3, Timestamp.from(ahora));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudo consumir el token de reseteo", e);
        }
    }

    /**
     * Voids every other outstanding token for the user.
     *
     * <p>Someone who requested three links and used one should not be left with
     * two live ones. It also limits the damage of a link that leaked into a log
     * or a browser history: a completed reset invalidates it.</p>
     */
    public int anularPendientesDe(Connection c, UUID usuarioId, Instant ahora) {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE password_reset_token
                   SET consumed_at = ?
                 WHERE usuario_id = ? AND consumed_at IS NULL
                """)) {
            ps.setTimestamp(1, Timestamp.from(ahora));
            ps.setObject(2, usuarioId);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudieron anular los tokens pendientes", e);
        }
    }

    /** Convenience for callers with no transaction of their own (tests, diagnostics). */
    public Optional<UUID> consumir(String rawToken, Instant ahora) {
        try (Connection c = dataSource.getConnection()) {
            return consumir(c, rawToken, ahora);
        } catch (java.sql.SQLException e) {
            throw new UsuarioRepository.DatabaseException("no se pudo consumir el token de reseteo", e);
        }
    }
}
