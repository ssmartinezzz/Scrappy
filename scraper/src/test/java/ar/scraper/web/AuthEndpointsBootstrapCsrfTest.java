package ar.scraper.web;

import ar.scraper.config.AllowedOrigins;
import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.JwtAuthFilter;
import ar.scraper.security.PasswordHasher;
import ar.scraper.security.RefreshCookie;
import ar.scraper.security.RefreshTokenService;
import ar.scraper.security.SecurityConfig;
import ar.scraper.security.TokenService;
import ar.scraper.security.reset.PasswordResetService;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * frontend-auth-ui, Phase 2 (task 2.6) — the bootstrap-CSRF admission matrix,
 * exercised at the HTTP layer so the headers really are read from the request
 * rather than from a direct Java call.
 *
 * <p>{@link RefreshTokenService} is mocked here and made to answer exactly the
 * way the real {@code rotar(token, nonce, bootstrapAdmitido)} contract does for
 * a nonce-less call: {@code Rotada} when {@code bootstrapAdmitido} is
 * {@code true}, {@code CsrfInvalido} when it is {@code false}. That isolates
 * what this test actually owns — whether {@link AuthEndpoints#refresh} computes
 * the flag correctly from {@code Origin}/{@code Sec-Fetch-Site} — from whether
 * {@link RefreshTokenService} honours it, which
 * {@link ar.scraper.security.RefreshTokenRotationTest} already covers directly
 * against a real database.</p>
 *
 * <p><b>Must import {@code SecurityConfig}, {@code JwtAuthFilter} and
 * {@code TokenService}</b> — {@code @WebMvcTest} registers {@code Filter}s but
 * not ordinary {@code @Component}s, so without all three the security context
 * this slice needs never loads (recorded trap).</p>
 */
@WebMvcTest(controllers = AuthEndpoints.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, TokenService.class, AllowedOrigins.class})
@TestPropertySource(properties = {
        "auth.jwt.secret=un-secreto-de-al-menos-32-bytes-para-hs256",
        "app.cors.allowed-origins=http://localhost:5173,http://localhost:8080"
})
@Epic("Security")
@Feature("Refresh tokens")
@Story("Bootstrap-CSRF admission — Origin allow-list + Sec-Fetch-Site")
@DisplayName("AuthEndpoints — bootstrap refresh admission (Origin + Sec-Fetch-Site)")
class AuthEndpointsBootstrapCsrfTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UsuarioRepository usuarios;
    @MockBean
    private PasswordHasher hasher;
    @MockBean
    private RefreshTokenService sesiones;
    @MockBean
    private PasswordResetService reseteos;

    @BeforeEach
    void setUp() {
        // Nonce-less calls: mirror the real rotar() bootstrap contract exactly.
        when(sesiones.rotar(anyString(), isNull(), anyBoolean())).thenAnswer(inv -> {
            boolean admitido = inv.getArgument(2);
            return admitido
                    ? new RefreshTokenService.Rotada("nuevo-access",
                            new RefreshTokenService.Sesion("nuevo-refresh", "nuevo-nonce",
                                    UUID.randomUUID(), Instant.now().plusSeconds(3600)))
                    : new RefreshTokenService.CsrfInvalido();
        });
        // A present-but-wrong nonce: never admitted, regardless of bootstrapAdmitido.
        when(sesiones.rotar(anyString(), eq("nonce-equivocado"), anyBoolean()))
                .thenReturn(new RefreshTokenService.CsrfInvalido());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder refreshCon(String origin, String secFetchSite) {
        var req = post("/api/auth/refresh").cookie(new Cookie(RefreshCookie.NOMBRE, "cualquier-refresh-token"));
        if (origin != null) {
            req = req.header("Origin", origin);
        }
        if (secFetchSite != null) {
            req = req.header("Sec-Fetch-Site", secFetchSite);
        }
        return req;
    }

    @Test
    @DisplayName("allow-listed Origin + same-origin admits a nonce-less refresh")
    void allowListedOriginSameOriginAdmits() throws Exception {
        mockMvc.perform(refreshCon("http://localhost:5173", "same-origin"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("allow-listed Origin + same-site admits too — both shipped topologies are cross-origin")
    void allowListedOriginSameSiteAdmits() throws Exception {
        mockMvc.perform(refreshCon("http://localhost:8080", "same-site"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a foreign-port Origin is rejected even with a trusted Sec-Fetch-Site")
    void foreignPortOriginIsRejected() throws Exception {
        // Rejected before it ever reaches AuthEndpoints: CorsConfig and
        // AllowedOrigins read the SAME app.cors.allowed-origins property, so
        // Spring's own CorsProcessor already blocks a non-allow-listed Origin
        // at the filter layer — a plain-text 403, not our JSON error body. The
        // property under test (no session for a foreign-port page) holds either
        // way; the other admission-matrix cases below prove the bootstrap check
        // itself, since CORS has no opinion on Sec-Fetch-Site or the nonce.
        mockMvc.perform(refreshCon("http://localhost:9999", "same-site"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Origin absent is rejected regardless of Sec-Fetch-Site")
    void missingOriginIsRejected() throws Exception {
        mockMvc.perform(refreshCon(null, "same-origin"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("csrf_invalido"));
    }

    @Test
    @DisplayName("Sec-Fetch-Site absent is rejected even from an allow-listed Origin")
    void missingSecFetchSiteIsRejected() throws Exception {
        mockMvc.perform(refreshCon("http://localhost:5173", null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("csrf_invalido"));
    }

    @Test
    @DisplayName("Sec-Fetch-Site: cross-site is rejected from an allow-listed Origin")
    void crossSiteSecFetchSiteIsRejected() throws Exception {
        mockMvc.perform(refreshCon("http://localhost:5173", "cross-site"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("csrf_invalido"));
    }

    @Test
    @DisplayName("Sec-Fetch-Site: none is rejected from an allow-listed Origin")
    void noneSecFetchSiteIsRejected() throws Exception {
        mockMvc.perform(refreshCon("http://localhost:5173", "none"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("csrf_invalido"));
    }

    @Test
    @DisplayName("2.8 — a present but wrong nonce is 403 even with perfect bootstrap headers")
    void aPresentButWrongNonceIsRejectedRegardless() throws Exception {
        mockMvc.perform(refreshCon("http://localhost:5173", "same-origin")
                        .header(AuthEndpoints.CSRF_HEADER, "nonce-equivocado"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("csrf_invalido"));
    }
}
