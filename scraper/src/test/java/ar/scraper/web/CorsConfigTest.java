package ar.scraper.web;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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
 * {@code allowCredentials=false} throughout (no auth/session cookie exists in
 * this API), matching design D6.
 */
@WebMvcTest(controllers = RootController.class)
@Import(CorsConfig.class)
@TestPropertySource(properties = "app.cors.allowed-origins=http://localhost:5173")
@Epic("REST API")
@Feature("CORS configuration")
@Story("API-only backend cross-origin policy")
@DisplayName("CorsConfig — allowed vs disallowed origins, non-SPA root response")
class CorsConfigTest {

    @Autowired
    private MockMvc mockMvc;

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
