package ar.scraper.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@code password_reset_token}. Two people (or the same person clicking twice, or
 * an attacker racing a victim) would then both succeed with a token that is documented as
 * single-use.
 */
@Repository
public class PasswordResetRepository {

    private final JdbcTemplate jdbc;

    public PasswordResetRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** Same digest as the refresh tokens — see that class for why SHA-256 and not Argon2id. */
    public static String hash(String rawToken) {
        return RefreshTokenRepository.hash(rawToken);
    }

    public void crear(UUID usuarioId, String rawToken, Instant expiraEn) {
        try {
            jdbc.update("""
                    INSERT INTO password_reset_token (token_hash, usuario_id, expires_at)
                    VALUES (?, ?, ?)
                    """, ps -> {
                ps.setString(1, hash(rawToken));
                ps.setObject(2, usuarioId);
                ps.setTimestamp(3, Timestamp.from(expiraEn));
            });
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudo crear el token de reseteo", e);
        }
    }

    /**
     * Atomically marks the token consumed and reports whose it was. Joins the caller's transaction,
     * so the password change that follows can roll the consumption back..
     */
    public Optional<UUID> consumir(String rawToken, Instant ahora) {
        try {
            return jdbc.query("""
                UPDATE password_reset_token
                   SET consumed_at = ?
                 WHERE token_hash = ?
                   AND consumed_at IS NULL
                   AND expires_at > ?
             RETURNING usuario_id
                """, ps -> {
                ps.setTimestamp(1, Timestamp.from(ahora));
                ps.setString(2, hash(rawToken));
                ps.setTimestamp(3, Timestamp.from(ahora));
            }, rs -> rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.<UUID>empty());
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudo consumir el token de reseteo", e);
        }
    }

    /** Someone who requested three links and used one should not be left with two live ones. */
    public int anularPendientesDe(UUID usuarioId, Instant ahora) {
        try {
            return jdbc.update("""
                UPDATE password_reset_token
                   SET consumed_at = ?
                 WHERE usuario_id = ? AND consumed_at IS NULL
                """, ps -> {
                ps.setTimestamp(1, Timestamp.from(ahora));
                ps.setObject(2, usuarioId);
            });
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudieron anular los tokens pendientes", e);
        }
    }
}
