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
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-accounts-and-roles, slice 4 — {@code POST}/{@code DELETE
 * /api/auth/refresh}, with the CSRF nonce.
 *
 * <h3>Why the refresh endpoint needs CSRF protection and nothing else does</h3>
 *
 * <p>Every other route in this API authenticates with a bearer header, and a
 * header is never attached automatically — a page on another origin simply
 * cannot make the browser send one. The refresh endpoint is the single place
 * that carries an <b>ambient</b> credential: the browser attaches the cookie on
 * its own, whether or not the page asking for it is ours.</p>
 *
 * <p>{@code SameSite=Strict} is the first line and is not enough here, for a
 * reason specific to this application: <b>same-site determination ignores the
 * port</b>. Anything served from any other port on {@code localhost} — a dev
 * server, another project, something a developer ran once — is same-site with
 * this backend, and the cookie would be attached for it. The double-submit
 * nonce closes exactly that gap, because it travels in a header the attacking
 * page cannot set.</p>
 *
 * <h3>The ordering test is the important one</h3>
 *
 * <p>{@link #aForgedNonceLeavesTheRefreshTokenIntact} asserts that a rejected
 * request leaves the presented token <b>still usable</b>. Verify the nonce after
 * consuming the token and the endpoint still "rejects" the attack — but it has
 * already burnt the real client's token, so the client's next legitimate refresh
 * trips reuse detection and the user is logged out. That turns a blocked attack
 * into a successful denial of service, which is most of what the attacker
 * wanted. The rejection is not the property worth testing; the intact token is.</p>
 */
@Epic("Security")
@Feature("Refresh tokens")
@Story("POST/DELETE /api/auth/refresh, nonce-protected")
@DisplayName("AuthEndpoints — refresh and logout")
class AuthEndpointsRefreshTest extends PostgresTestBase {

    private static final String SECRETO = "un-secreto-de-al-menos-32-bytes-para-hs256";
    private static final String PASSWORD = "la-password-correcta";

    private AuthEndpoints endpoints;
    private TokenService tokens;
    private UsuarioRepository usuarios;
    private RefreshTokenService sesiones;
    private UUID ana;

    @BeforeEach
    void setUp() {
        usuarios = new UsuarioRepository(dataSource());
        PasswordHasher hasher = new PasswordHasher();
        tokens = new TokenService(SECRETO, Clock.systemUTC());
        sesiones = new RefreshTokenService(
                new RefreshTokenRepository(dataSource()), tokens, Clock.systemUTC());
        endpoints = new AuthEndpoints(usuarios, hasher, tokens, sesiones, null);

        usuarios.crear("ana", null, hasher.hash(PASSWORD), false);
        ana = usuarios.buscarActivaPorUsername("ana").orElseThrow().id();
    }

    // ── 4.8 · the happy path ─────────────────────────────────────────────────

    @Test
    @DisplayName("a valid cookie plus the right nonce rotates and returns a fresh pair")
    void aValidRefreshRotates() {
        RefreshTokenService.Sesion sesion = sesiones.abrir(ana);

        ResponseEntity<ObjectNode> resp = endpoints.refresh(sesion.refreshToken(), sesion.csrfNonce());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(tokens.verificar(resp.getBody().get("accessToken").asText())).contains(ana);
        assertThat(resp.getBody().get("csrfNonce").asText())
                .as("a nonce that survived the rotation would outlive the token it protects")
                .isNotEqualTo(sesion.csrfNonce());
        assertThat(cookieDe(resp)).contains("HttpOnly").contains("Path=" + RefreshCookie.PATH);
    }

    @Test
    @DisplayName("the rotated refresh token never appears in the response body")
    void theNewRefreshTokenStaysInTheCookie() {
        RefreshTokenService.Sesion sesion = sesiones.abrir(ana);

        ResponseEntity<ObjectNode> resp = endpoints.refresh(sesion.refreshToken(), sesion.csrfNonce());

        String cookie = cookieDe(resp);
        String valor = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        assertThat(resp.getBody().toString()).doesNotContain(valor);
    }

    // ── 4.10 · the ordering property ─────────────────────────────────────────

    @Test
    @DisplayName("a forged nonce is rejected 403 AND leaves the refresh token usable")
    void aForgedNonceLeavesTheRefreshTokenIntact() {
        RefreshTokenService.Sesion sesion = sesiones.abrir(ana);

        ResponseEntity<ObjectNode> forjado = endpoints.refresh(sesion.refreshToken(), "nonce-inventado");

        assertThat(forjado.getStatusCode().value()).isEqualTo(403);
        assertThat(endpoints.refresh(sesion.refreshToken(), sesion.csrfNonce()).getStatusCode().value())
                .as("if the token had been consumed first, the real client's next refresh would "
                        + "trip reuse detection — a blocked attack turned into a forced logout")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a missing nonce is rejected the same way")
    void aMissingNonceIsRejected() {
        RefreshTokenService.Sesion sesion = sesiones.abrir(ana);

        assertThat(endpoints.refresh(sesion.refreshToken(), null).getStatusCode().value()).isEqualTo(403);
        assertThat(endpoints.refresh(sesion.refreshToken(), sesion.csrfNonce()).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("no cookie at all is 401, not 403 — there is no session to protect")
    void noCookieIsUnauthorized() {
        assertThat(endpoints.refresh(null, "cualquiera").getStatusCode().value()).isEqualTo(401);
    }

    // ── reuse, seen from the endpoint ────────────────────────────────────────

    @Test
    @DisplayName("a token reused past the grace window returns 401 and clears the cookie")
    void reuseIsAnsweredWithACleanSlate() throws Exception {
        RefreshTokenService.Sesion sesion = sesiones.abrir(ana);
        endpoints.refresh(sesion.refreshToken(), sesion.csrfNonce());

        // Age the row past the grace window without sleeping.
        envejecerRotacion();

        ResponseEntity<ObjectNode> resp = endpoints.refresh(sesion.refreshToken(), sesion.csrfNonce());

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(cookieDe(resp))
                .as("leaving the cookie in place would have the browser re-present a token that "
                        + "can only ever fail from here on")
                .contains("Max-Age=0");
    }

    // ── 4.13 · logout ────────────────────────────────────────────────────────

    @Test
    @DisplayName("logout revokes the family and clears the cookie with a matching name and path")
    void logoutRevokesAndClears() {
        RefreshTokenService.Sesion sesion = sesiones.abrir(ana);

        ResponseEntity<ObjectNode> resp = endpoints.logout(sesion.refreshToken(), sesion.csrfNonce());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("cerrada").asBoolean()).isTrue();
        assertThat(cookieDe(resp))
                .as("a cookie is identified by name+path: clearing it on another path deletes "
                        + "nothing and looks like it worked")
                .contains(RefreshCookie.NOMBRE + "=")
                .contains("Path=" + RefreshCookie.PATH)
                .contains("Max-Age=0");

        assertThat(endpoints.refresh(sesion.refreshToken(), sesion.csrfNonce()).getStatusCode().value())
                .as("the server-side revocation is the real work; clearing the cookie is hygiene")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("logout with a forged nonce does not close the session")
    void logoutIsNonceProtectedToo() {
        RefreshTokenService.Sesion sesion = sesiones.abrir(ana);

        ResponseEntity<ObjectNode> resp = endpoints.logout(sesion.refreshToken(), "nonce-inventado");

        assertThat(resp.getBody().get("cerrada").asBoolean())
                .as("otherwise any cross-site page could log the user out at will")
                .isFalse();
        assertThat(endpoints.refresh(sesion.refreshToken(), sesion.csrfNonce()).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("logout still clears the browser's cookie even when the token is unknown")
    void logoutAlwaysClearsTheCookie() {
        ResponseEntity<ObjectNode> resp = endpoints.logout("un-token-desconocido", "x");

        assertThat(resp.getBody().get("cerrada").asBoolean()).isFalse();
        assertThat(cookieDe(resp)).contains("Max-Age=0");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String cookieDe(ResponseEntity<ObjectNode> resp) {
        String cookie = resp.getHeaders().getFirst("Set-Cookie");
        assertThat(cookie).as("la respuesta trae Set-Cookie").isNotNull();
        return cookie;
    }

    /** Pushes every rotation far enough into the past that the grace window has closed. */
    private void envejecerRotacion() throws Exception {
        try (java.sql.Connection c = dataSource().getConnection();
             java.sql.Statement st = c.createStatement()) {
            st.execute("UPDATE refresh_token SET rotated_at = rotated_at - interval '1 hour' "
                    + "WHERE rotated_at IS NOT NULL");
        }
        // The replay cache is in-memory and keyed by the spent token, so a new
        // service instance is what "the window has closed" looks like from here.
        sesiones = new RefreshTokenService(
                new RefreshTokenRepository(dataSource()), tokens, Clock.systemUTC());
        endpoints = new AuthEndpoints(usuarios, new PasswordHasher(), tokens, sesiones, null);
    }
}
