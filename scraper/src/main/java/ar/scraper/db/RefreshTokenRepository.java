package ar.scraper.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * SHA-256, not Argon2id — the opposite choice from passwords, for the opposite reason. Argon2id is
 * slow on purpose because a password is a short, guessable, human-chosen string and the defence is
 * to make each guess expensive.
 */
@Repository
public class RefreshTokenRepository {

    private final JdbcTemplate jdbc;

    public RefreshTokenRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public record Fila(long id,
                       UUID usuarioId,
                       UUID familyId,
                       String csrfNonce,
                       Instant expiresAt,
                       Instant rotatedAt,
                       Instant revokedAt) {

        public boolean rotado() {
            return rotatedAt != null;
        }

        public boolean revocado() {
            return revokedAt != null;
        }
    }

    public static String hash(String rawToken) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }

    public void crear(UUID usuarioId, String rawToken, UUID familyId, String csrfNonce, Instant expiresAt) {
        try {
            jdbc.update("""
                    INSERT INTO refresh_token (token_hash, family_id, csrf_nonce, usuario_id, expires_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, ps -> {
                ps.setString(1, hash(rawToken));
                ps.setObject(2, familyId);
                ps.setString(3, csrfNonce);
                ps.setObject(4, usuarioId);
                ps.setTimestamp(5, Timestamp.from(expiresAt));
            });
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudo guardar el refresh token", e);
        }
    }

    public Optional<Fila> buscar(String rawToken) {
        try {
            return jdbc.query("""
                    SELECT id, usuario_id, family_id, csrf_nonce, expires_at, rotated_at, revoked_at
                    FROM refresh_token WHERE token_hash = ?
                    """, ps -> ps.setString(1, hash(rawToken)), rs -> {
                if (!rs.next()) {
                    return Optional.<Fila>empty();
                }
                return Optional.of(new Fila(
                        rs.getLong(1),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class),
                        rs.getString(4),
                        instante(rs.getTimestamp(5)),
                        instante(rs.getTimestamp(6)),
                        instante(rs.getTimestamp(7))));
            });
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudo leer el refresh token", e);
        }
    }

    /** Marks the row rotated, but only if it was not already.. */
    public boolean marcarRotado(long id, Instant cuando) {
        try {
            return jdbc.update("UPDATE refresh_token SET rotated_at = ? WHERE id = ? AND rotated_at IS NULL", ps -> {
                ps.setTimestamp(1, Timestamp.from(cuando));
                ps.setLong(2, id);
            }) == 1;
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudo marcar el refresh token como rotado", e);
        }
    }

    /** Idempotent: already-revoked rows stay as they were. */
    public int revocarFamilia(UUID familyId, Instant cuando) {
        try {
            return jdbc.update("UPDATE refresh_token SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL", ps -> {
                ps.setTimestamp(1, Timestamp.from(cuando));
                ps.setObject(2, familyId);
            });
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudo revocar la familia de tokens", e);
        }
    }

    /**
     * Used by the password-reset flow: a reset that left other devices signed in would be useless
     * as a remedy for the case people actually reset a password in — somebody else is already
     * inside.
     */
    public int revocarTodasLasDe(UUID usuarioId, Instant cuando) {
        try {
            return jdbc.update("UPDATE refresh_token SET revoked_at = ? WHERE usuario_id = ? AND revoked_at IS NULL", ps -> {
                ps.setTimestamp(1, Timestamp.from(cuando));
                ps.setObject(2, usuarioId);
            });
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudieron revocar los tokens del usuario", e);
        }
    }

    private static Instant instante(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
