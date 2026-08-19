package ar.scraper.db;

import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@code refresh_token}.
 *
 * <p><b>SHA-256, not Argon2id</b> — the opposite choice from passwords, for the
 * opposite reason. Argon2id is slow on purpose because a password is a short,
 * guessable, human-chosen string and the defence is to make each guess
 * expensive. A refresh token is 256 bits from a CSPRNG: there is no dictionary
 * to walk and no guess worth slowing down. What hashing buys here is that a
 * stolen database dump is not a stack of working sessions, and a fast digest
 * buys exactly that. Making it slow would only tax the legitimate lookup that
 * happens on every refresh.</p>
 *
 * <p>The column is {@code token_hash}, never {@code token}: a column called
 * {@code token} eventually gets a token written into it.</p>
 */
@Repository
public class RefreshTokenRepository {

    private final DataSource dataSource;

    public RefreshTokenRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** A stored token, as the state machine needs to see it. */
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
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO refresh_token (token_hash, family_id, csrf_nonce, usuario_id, expires_at)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
            ps.setString(1, hash(rawToken));
            ps.setObject(2, familyId);
            ps.setString(3, csrfNonce);
            ps.setObject(4, usuarioId);
            ps.setTimestamp(5, Timestamp.from(expiresAt));
            ps.executeUpdate();
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudo guardar el refresh token", e);
        }
    }

    public Optional<Fila> buscar(String rawToken) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                    SELECT id, usuario_id, family_id, csrf_nonce, expires_at, rotated_at, revoked_at
                    FROM refresh_token WHERE token_hash = ?
                    """)) {
            ps.setString(1, hash(rawToken));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Fila(
                        rs.getLong(1),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class),
                        rs.getString(4),
                        instante(rs.getTimestamp(5)),
                        instante(rs.getTimestamp(6)),
                        instante(rs.getTimestamp(7))));
            }
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudo leer el refresh token", e);
        }
    }

    /**
     * Marks the row rotated, but only if it was not already.
     *
     * @return {@code true} when this call did the rotating. Two concurrent
     *         refreshes with the same token therefore produce exactly one
     *         winner, and the loser is a genuine reuse rather than a race the
     *         state machine has to guess about.
     */
    public boolean marcarRotado(long id, Instant cuando) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE refresh_token SET rotated_at = ? WHERE id = ? AND rotated_at IS NULL")) {
            ps.setTimestamp(1, Timestamp.from(cuando));
            ps.setLong(2, id);
            return ps.executeUpdate() == 1;
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudo marcar el refresh token como rotado", e);
        }
    }

    /** Revokes every token in the family. Idempotent: already-revoked rows stay as they were. */
    public int revocarFamilia(UUID familyId, Instant cuando) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE refresh_token SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL")) {
            ps.setTimestamp(1, Timestamp.from(cuando));
            ps.setObject(2, familyId);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudo revocar la familia de tokens", e);
        }
    }

    /** Used by the password-reset flow (slice 6) and by account deactivation. */
    public int revocarTodasLasDe(UUID usuarioId, Instant cuando) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE refresh_token SET revoked_at = ? WHERE usuario_id = ? AND revoked_at IS NULL")) {
            ps.setTimestamp(1, Timestamp.from(cuando));
            ps.setObject(2, usuarioId);
            return ps.executeUpdate();
        } catch (Exception e) {
            throw new UsuarioRepository.DatabaseException("no se pudieron revocar los tokens del usuario", e);
        }
    }

    private static Instant instante(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
