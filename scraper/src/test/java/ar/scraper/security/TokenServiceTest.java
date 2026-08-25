package ar.scraper.security;

import com.nimbusds.jwt.SignedJWT;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * user-accounts-and-roles, slice 2 — the access token.
 *
 * <p>The assertion that matters most is the negative one: <b>the token carries
 * no role claim</b>. A role inside a signed token cannot be revoked before it
 * expires, so an account demoted from ADMIN would keep its powers for the rest
 * of the token's life while the database says otherwise. The role is therefore
 * re-read from the database on every request (slice 7), and this test exists so
 * that nobody "optimises" that query away by putting the role back in the
 * token.</p>
 *
 * <p>Expiry is exercised through an injected {@link Clock} rather than by
 * sleeping: a test that waits fifteen minutes does not get run, and one that
 * waits a second is flaky on a loaded machine.</p>
 */
@Epic("Security")
@Feature("Access tokens")
@Story("HS256 access token carrying a subject and nothing else")
@DisplayName("TokenService — access token issuance and verification")
class TokenServiceTest {

    private static final String SECRETO = "un-secreto-de-al-menos-32-bytes-para-hs256";
    private static final Instant AHORA = Instant.parse("2026-08-19T12:00:00Z");

    private final Clock reloj = Clock.fixed(AHORA, ZoneOffset.UTC);
    private final TokenService service = new TokenService(SECRETO, reloj);

    @Test
    @DisplayName("a freshly issued token verifies back to its subject")
    void issuedTokenVerifiesBackToItsSubject() {
        UUID usuario = UUID.randomUUID();

        assertThat(service.verificar(service.emitir(usuario))).contains(usuario);
    }

    @Test
    @DisplayName("the token carries sub, iat, exp and jti — and no role claim")
    void theTokenCarriesNoRoleClaim() throws Exception {
        UUID usuario = UUID.randomUUID();

        SignedJWT jwt = SignedJWT.parse(service.emitir(usuario));

        assertThat(jwt.getJWTClaimsSet().getClaims().keySet())
                .as("a role inside a signed token cannot be revoked before it expires")
                .containsExactlyInAnyOrder("sub", "iat", "exp", "jti");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(usuario.toString());
    }

    @Test
    @DisplayName("the token is signed with HS256")
    void theTokenIsSignedWithHs256() throws Exception {
        SignedJWT jwt = SignedJWT.parse(service.emitir(UUID.randomUUID()));

        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
    }

    @Test
    @DisplayName("the token expires fifteen minutes after it is issued")
    void theTokenLastsFifteenMinutes() throws Exception {
        SignedJWT jwt = SignedJWT.parse(service.emitir(UUID.randomUUID()));

        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant())
                .isEqualTo(AHORA.plus(TokenService.TTL));
        assertThat(TokenService.TTL).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("two tokens for the same subject differ — jti is unique per issuance")
    void eachIssuanceHasItsOwnJti() throws Exception {
        UUID usuario = UUID.randomUUID();

        String uno = service.emitir(usuario);
        String otro = service.emitir(usuario);

        assertThat(uno).isNotEqualTo(otro);
        assertThat(SignedJWT.parse(uno).getJWTClaimsSet().getJWTID())
                .isNotEqualTo(SignedJWT.parse(otro).getJWTClaimsSet().getJWTID());
    }

    @Test
    @DisplayName("an expired token does not verify")
    void anExpiredTokenDoesNotVerify() {
        String token = service.emitir(UUID.randomUUID());

        TokenService masTarde = new TokenService(
                SECRETO, Clock.fixed(AHORA.plus(TokenService.TTL).plusSeconds(1), ZoneOffset.UTC));

        assertThat(masTarde.verificar(token)).isEmpty();
    }

    @Test
    @DisplayName("a token still inside its window verifies")
    void aTokenInsideItsWindowVerifies() {
        UUID usuario = UUID.randomUUID();
        String token = service.emitir(usuario);

        TokenService casiVencido = new TokenService(
                SECRETO, Clock.fixed(AHORA.plus(TokenService.TTL).minusSeconds(1), ZoneOffset.UTC));

        assertThat(casiVencido.verificar(token)).contains(usuario);
    }

    @Test
    @DisplayName("a token signed with another secret does not verify")
    void aTokenFromAnotherSecretDoesNotVerify() {
        String ajeno = new TokenService("otro-secreto-igualmente-largo-de-32-bytes", reloj)
                .emitir(UUID.randomUUID());

        assertThat(service.verificar(ajeno)).isEmpty();
    }

    @Test
    @DisplayName("a tampered payload does not verify")
    void aTamperedTokenDoesNotVerify() {
        String token = service.emitir(UUID.randomUUID());
        String[] partes = token.split("\\.");
        String otroPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + UUID.randomUUID() + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(service.verificar(partes[0] + "." + otroPayload + "." + partes[2])).isEmpty();
    }

    @Test
    @DisplayName("an unsigned 'alg: none' token does not verify")
    void anAlgNoneTokenDoesNotVerify() {
        String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + UUID.randomUUID() + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(service.verificar(header + "." + payload + "."))
                .as("algorithm confusion is the classic JWT hole — the verifier picks the algorithm, "
                        + "never the token")
                .isEmpty();
    }

    @Test
    @DisplayName("garbage does not verify and does not throw")
    void garbageDoesNotVerify() {
        assertThat(service.verificar("no-es-un-token")).isEmpty();
        assertThat(service.verificar("")).isEmpty();
        assertThat(service.verificar(null)).isEmpty();
    }

    @Test
    @DisplayName("a secret shorter than 32 bytes is refused at construction")
    void aShortSecretIsRefusedUpFront() {
        assertThatThrownBy(() -> new TokenService("corto", reloj))
                .as("HS256 with a weak key is a signature anyone can forge; failing at startup "
                        + "is the only place this is cheap to fix")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_JWT_SECRET");
    }
}
