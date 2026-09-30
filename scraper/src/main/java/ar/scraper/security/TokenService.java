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
import org.apache.commons.lang3.StringUtils;

/**
 * Authorization therefore re-reads the role from the database on every request, and the token's
 * only job is to say who is asking. HS256, not RS256.
 */
@Component
public class TokenService {

    /** See the class javadoc for why this is not configurable. */
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
            // Signing cannot fail on a valid key and a well-formed claims set, so reaching here
            // means the process is misconfigured, not that a caller sent something bad.
            throw new IllegalStateException("no se pudo firmar el access token", e);
        }
    }

    public Optional<UUID> verificar(String token) {
        if (StringUtils.isBlank(token)) {
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
     * "is this token ours" and "was it issued before the password changed" have different answers
     * and different consequences.
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
