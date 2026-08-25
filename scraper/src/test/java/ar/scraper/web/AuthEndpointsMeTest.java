package ar.scraper.web;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.JwtAuthFilter;
import ar.scraper.security.PasswordHasher;
import ar.scraper.security.RefreshTokenService;
import ar.scraper.security.SecurityConfig;
import ar.scraper.security.TokenService;
import ar.scraper.security.reset.PasswordResetService;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * frontend-auth-ui, Phase 3 (tasks 3.2/3.3) — {@code GET /api/auth/me}.
 *
 * <p><b>Filters ON</b>, same pattern as {@link ar.scraper.security.SecurityFilterChainIT}
 * and {@link AuthEndpointsMappingTest}: a real {@link SecurityConfig} and a real
 * {@link JwtAuthFilter} with a real signed token, so what is proven here is the
 * actual enforcement path, not a stand-in. Unlike
 * {@code SecurityFilterChainIT}, this exercises the real {@link AuthEndpoints}
 * controller — the thing under test <i>is</i> what {@code /me} reads off the
 * security context, which a fixture controller cannot demonstrate.</p>
 *
 * <p><b>The fixture seeds the same role it puts in the security context.</b> The
 * role comes from the database on every request, not from the token, so seeding
 * VIEWER in the mock while asserting VIEWER in the response is not redundant —
 * a mismatched fixture here would fail for a reason unrelated to what this test
 * means to prove (recorded trap).</p>
 */
@WebMvcTest(controllers = AuthEndpoints.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, TokenService.class})
@TestPropertySource(properties = {
        "auth.jwt.secret=un-secreto-de-al-menos-32-bytes-para-hs256",
        "app.cors.allowed-origins=http://localhost:5173"
})
@Epic("Security")
@Feature("Current subject")
@Story("GET /api/auth/me — username + roles, read from the DB per request")
@DisplayName("AuthEndpoints — GET /api/auth/me")
class AuthEndpointsMeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokens;

    @MockBean
    private UsuarioRepository usuarios;
    @MockBean
    private PasswordHasher hasher;
    @MockBean
    private RefreshTokenService sesiones;
    @MockBean
    private PasswordResetService reseteos;

    @Test
    @DisplayName("3.2 — anonymous GET /api/auth/me is 401, not 200 with empty data")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("3.3 — returns {username, roles:[...]} sourced from the DB row, roles as an array")
    void returnsUsernameAndRolesFromTheDatabase() throws Exception {
        UUID id = UUID.randomUUID();
        when(usuarios.autorizacionDe(id)).thenReturn(Optional.of(
                new UsuarioRepository.Autorizacion("valeria", List.of("VIEWER"), null)));
        String token = tokens.emitir(id);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("valeria"))
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles[0]").value("VIEWER"))
                .andExpect(jsonPath("$.roles.length()").value(1));
    }

    @Test
    @DisplayName("an ADMIN sees ADMIN in the roles array")
    void adminRoleIsReflected() throws Exception {
        UUID id = UUID.randomUUID();
        when(usuarios.autorizacionDe(id)).thenReturn(Optional.of(
                new UsuarioRepository.Autorizacion("admin-user", List.of("ADMIN"), null)));
        String token = tokens.emitir(id);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"));
    }

    @Test
    @DisplayName("a deactivated account's stale token is 401, exactly like every other route")
    void deactivatedAccountTokenIsUnauthorized() throws Exception {
        UUID id = UUID.randomUUID();
        when(usuarios.autorizacionDe(id)).thenReturn(Optional.empty());
        String token = tokens.emitir(id);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
