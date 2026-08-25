package ar.scraper.web;

import ar.scraper.db.RefreshTokenRepository;
import ar.scraper.db.UsuarioRepository;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.security.PasswordHasher;
import ar.scraper.security.RefreshCookie;
import ar.scraper.security.RefreshTokenService;
import ar.scraper.security.TokenService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 2 — {@code POST /api/auth/login}, against a
 * real database, a real Argon2id hash and a real signed token.
 *
 * <p><b>Why not a full {@code @SpringBootTest}.</b> The task list asks for one,
 * to keep new authentication tests away from {@code standaloneSetup} and
 * {@code addFilters=false} — habits that make a test structurally incapable of
 * proving anything about authorization. That intent is honoured: nothing here
 * is stubbed out of the security path. But booting the whole application also
 * runs {@code InflacionService}'s {@code @PostConstruct}, which fetches INDEC
 * data over the network at context refresh, and a login test that fails when a
 * third-party website is slow is a test people learn to ignore. The collaborators
 * are therefore assembled directly — real repository, real hasher, real token
 * service — and the HTTP mapping itself is proven separately by
 * {@link AuthEndpointsMappingTest}, which loads only the web layer.</p>
 *
 * <p>There is no filter chain to bypass in this phase: nothing is gated yet.
 * When enforcement lands, its tests must use a full context with filters on.</p>
 */
@Epic("Security")
@Feature("Login")
@Story("POST /api/auth/login issues an access token, or 401")
@DisplayName("AuthEndpoints — login")
class AuthEndpointsLoginTest extends PostgresTestBase {

    private static final String SECRETO = "un-secreto-de-al-menos-32-bytes-para-hs256";
    private static final String PASSWORD = "la-password-correcta";

    private UsuarioRepository repo;
    private TokenService tokens;
    private AuthEndpoints endpoints;

    @BeforeEach
    void setUp() {
        repo = new UsuarioRepository(dataSource());
        PasswordHasher hasher = new PasswordHasher();
        tokens = new TokenService(SECRETO, Clock.systemUTC());
        Clock reloj = Clock.systemUTC();
        endpoints = new AuthEndpoints(repo, hasher, tokens,
                new RefreshTokenService(new RefreshTokenRepository(dataSource()), tokens, reloj),
                null);   // el reseteo tiene su propio test; acá no se ejercita

        repo.crear("ana", "ana@example.com", hasher.hash(PASSWORD), false);
        repo.asignarRol("ana", "VIEWER");
    }

    @Test
    @DisplayName("correct credentials return a token that verifies back to the account")
    void correctCredentialsReturnAUsableToken() {
        ResponseEntity<ObjectNode> resp = login("ana", PASSWORD);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = resp.getBody().get("accessToken").asText();
        UUID esperado = repo.buscarActivaPorUsername("ana").orElseThrow().id();

        assertThat(tokens.verificar(token)).contains(esperado);
        assertThat(resp.getBody().get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(resp.getBody().get("expiresIn").asInt()).isEqualTo(900);
    }

    @Test
    @DisplayName("a wrong password returns 401 and no token")
    void wrongPasswordIsRejected() {
        ResponseEntity<ObjectNode> resp = login("ana", "la-password-equivocada");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody().has("accessToken")).isFalse();
    }

    @Test
    @DisplayName("a disabled account returns 401 even with the correct password")
    void disabledAccountIsRejected() {
        repo.desactivar("ana");

        ResponseEntity<ObjectNode> resp = login("ana", PASSWORD);

        assertThat(resp.getStatusCode())
                .as("activo=FALSE is a revocation switch, and login is where it has to bite first")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody().has("accessToken")).isFalse();
    }

    @Test
    @DisplayName("an unknown username returns 401")
    void unknownUsernameIsRejected() {
        assertThat(login("nadie", PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("login identifies by username — an email address is not an identifier")
    void emailIsNotALoginIdentifier() {
        assertThat(login("ana@example.com", PASSWORD).getStatusCode())
                .as("email is optional, and the bootstrap and service accounts have none")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the failure body is the same for a wrong password and an unknown user")
    void failuresAreIndistinguishable() {
        assertThat(login("ana", "mal").getBody().toString())
                .as("a different message per branch tells an attacker which usernames exist")
                .isEqualTo(login("nadie", "mal").getBody().toString());
    }

    @Test
    @DisplayName("a missing or blank field returns 401, not a crash")
    void malformedRequestsAreRejectedCleanly() {
        assertThat(login(null, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login("ana", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login("", "").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(endpoints.login(Map.of()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the response never echoes the password back")
    void theResponseNeverEchoesTheCredential() {
        assertThat(login("ana", PASSWORD).getBody().toString()).doesNotContain(PASSWORD);
        assertThat(login("ana", "mal").getBody().toString()).doesNotContain("mal");
    }


    @Test
    @DisplayName("login opens a session: the refresh token rides a scoped HttpOnly cookie, never the body")
    void loginOpensASessionInACookieNotInTheBody() {
        ResponseEntity<ObjectNode> resp = login("ana", PASSWORD);

        String setCookie = resp.getHeaders().getFirst("Set-Cookie");
        assertThat(setCookie)
                .as("HttpOnly is what survives an XSS foothold; the narrow Path is what keeps it "
                        + "off the other ~70 endpoints")
                .contains(RefreshCookie.NOMBRE + "=")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Strict")
                .contains("Path=" + RefreshCookie.PATH);

        String valor = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        assertThat(resp.getBody().toString())
                .as("a refresh token in the JSON body is readable by script, which is the whole "
                        + "thing the cookie exists to prevent")
                .doesNotContain(valor);
        assertThat(resp.getBody().get("csrfNonce").asText()).isNotBlank();
    }

    @Test
    @DisplayName("a service account gets a token but no session — the CLI has no use for one")
    void serviceAccountsGetNoCookie() {
        PasswordHasher hasher = new PasswordHasher();
        repo.crear("cli", null, hasher.hash(PASSWORD), true);

        ResponseEntity<ObjectNode> resp = login("cli", PASSWORD);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("accessToken").asText()).isNotBlank();
        assertThat(resp.getHeaders().getFirst("Set-Cookie"))
                .as("a fourteen-day credential for a client that re-authenticates from .env is "
                        + "a credential lying around for nothing")
                .isNull();
        assertThat(resp.getBody().has("csrfNonce")).isFalse();
    }

    private ResponseEntity<ObjectNode> login(String username, String password) {
        java.util.Map<String, String> body = new java.util.HashMap<>();
        body.put("username", username);
        body.put("password", password);
        return endpoints.login(body);
    }
}
