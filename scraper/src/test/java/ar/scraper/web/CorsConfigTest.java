package ar.scraper.web;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.JwtAuthFilter;
import ar.scraper.security.SecurityConfig;
import ar.scraper.security.TokenService;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RED→GREEN coverage for {@link CorsConfig} (decouple-services-postgres,
 * Batch 3, tasks 3.3/3.4, design D6). Verifies:
 * <ul>
 *   <li>a request whose {@code Origin} matches {@code APP_CORS_ALLOWED_ORIGINS}
 *       (surfaced here as {@code app.cors.allowed-origins}) is allowed and
 *       echoes {@code Access-Control-Allow-Origin};</li>
 *   <li>a request from a disallowed origin is rejected (Spring's
 *       {@code DefaultCorsProcessor} answers 403 for any request — preflight
 *       or actual — carrying an {@code Origin} header that doesn't match the
 *       configured allow-list);</li>
 *   <li>{@code GET /} returns a small JSON status payload ({@link RootController}),
 *       not the old SPA {@code index.html} forward.</li>
 * </ul>
 * <p><b>The real {@link SecurityConfig} is imported since the enforcement slice</b>,
 * so these assertions now run through the actual filter chain. That matters for
 * {@code GET /}: it is on the permit list, so its 200 still holds — and it now
 * <i>means</i> something, because the same request to any unlisted path would be
 * refused. Without the import the chain here would be Boot's default one, and
 * this test would be describing a configuration the application does not use.</p>
 *
 * <p>{@code allowCredentials=false} on {@code /**}; the refresh path is the one
 * exception and has its own test in {@link CorsCredentialsTest}.</p>
 */
@WebMvcTest(controllers = RootController.class)
@Import({CorsConfig.class, SecurityConfig.class, JwtAuthFilter.class, TokenService.class})
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:5173",
        "auth.jwt.secret=un-secreto-de-al-menos-32-bytes-para-hs256"
})
@Epic("REST API")
@Feature("CORS configuration")
@Story("API-only backend cross-origin policy")
@DisplayName("CorsConfig — allowed vs disallowed origins, non-SPA root response")
class CorsConfigTest {

    @Autowired
    private MockMvc mockMvc;

    /** The filter chain needs it; what it returns is irrelevant to CORS. */
    @MockBean
    private UsuarioRepository usuarios;

    @Test
    @DisplayName("request from the configured allowed origin succeeds and echoes Access-Control-Allow-Origin")
    void allowedOriginSucceeds() throws Exception {
        mockMvc.perform(get("/").header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    @DisplayName("request from a disallowed origin is blocked (403)")
    void disallowedOriginIsBlocked() throws Exception {
        mockMvc.perform(get("/").header("Origin", "http://evil.example.com"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET / returns a non-SPA JSON status response, not index.html")
    void rootReturnsNonSpaJsonResponse() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(content().string(not(containsString("<html"))));
    }
}
