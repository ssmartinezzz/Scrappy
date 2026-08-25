package ar.scraper.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies the short-lived access token.
 *
 * <p><b>The token carries the subject and nothing else.</b> No role, no
 * permissions, no account state. That is not minimalism for its own sake: a
 * claim inside a signed token cannot be withdrawn before it expires, so a user
 * demoted from ADMIN — or disabled outright — would keep acting on the old
 * answer until the token ran out. Authorization therefore re-reads the role
 * from the database on every request, and the token's only job is to say who is
 * asking.</p>
 *
 * <p><b>HS256, not RS256.</b> There is exactly one issuer and one verifier —
 * this backend — so an asymmetric keypair buys nothing and costs key
 * distribution and rotation on a portable installer that has no PKI to lean on.
 * The algorithm is fixed at verification time and never read from the token
 * header, which closes the algorithm-confusion hole where an attacker presents
 * {@code alg: none} and the verifier obligingly agrees.</p>
 *
 * <p><b>Fifteen minutes</b> is short enough that a leaked token has a small
 * window and long enough that the refresh path is not hammered. It is a
 * constant rather than a setting: a deployment that tunes it is trading a
 * security property, which deserves a code change and a review, not an
 * environment variable.</p>
 */
@Component
public class TokenService {

    /** Access-token lifetime. See the class javadoc for why this is not configurable. */
    public static final Duration TTL = Duration.ofMinutes(15);

    /** HS256's key must be at least as long as its digest, or the signature is weak. */
    private static final int MIN_SECRET_BYTES = 32;

    private final byte[] secreto;
    private final Clock reloj;

    public TokenService(@Value("${auth.jwt.secret}") String secreto, Clock reloj) {
        byte[] bytes = secreto == null ? new byte[0] : secreto.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "AUTH_JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes for HS256; got "
                            + bytes.length + ". A short key makes the signature forgeable, which defeats "
                            + "the entire point of signing the token.");
        }
        this.secreto = bytes;
        this.reloj = reloj;
    }

    public String emitir(UUID usuarioId) {
        Instant ahora = reloj.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(usuarioId.toString())
                .issueTime(Date.from(ahora))
                .expirationTime(Date.from(ahora.plus(TTL)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secreto));
            return jwt.serialize();
        } catch (Exception e) {
            // Signing cannot fail on a valid key and a well-formed claims set, so
            // reaching here means the process is misconfigured, not that a caller
            // sent something bad.
            throw new IllegalStateException("no se pudo firmar el access token", e);
        }
    }

    /**
     * @return the subject when the token is well-formed, signed by us with HS256,
     *         and not expired; empty in every other case — including malformed
     *         input. A caller deciding whether to let a request through has no
     *         use for the distinction, and handing it an exception invites a
     *         {@code catch} that lets the request through.
     */
    public Optional<UUID> verificar(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);

            // The algorithm is asserted, never adopted from the header.
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
                return Optional.empty();
            }
            if (!jwt.verify(new MACVerifier(secreto))) {
                return Optional.empty();
            }
            Date vence = jwt.getJWTClaimsSet().getExpirationTime();
            if (vence == null || !reloj.instant().isBefore(vence.toInstant())) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(jwt.getJWTClaimsSet().getSubject()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * The token's {@code iat}, when it is one of ours and still valid.
     *
     * <p>Read separately from {@link #verificar} so the two questions stay
     * separate: "is this token ours" and "was it issued before the password
     * changed" have different answers and different consequences.</p>
     */
    public Optional<Instant> emitidoEn(String token) {
        if (verificar(token).isEmpty()) {
            return Optional.empty();
        }
        try {
            Date emitido = SignedJWT.parse(token).getJWTClaimsSet().getIssueTime();
            return Optional.ofNullable(emitido).map(Date::toInstant);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
