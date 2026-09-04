package ar.scraper.web;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.JwtAuthFilter;
import ar.scraper.security.SecurityConfig;
import ar.scraper.security.TokenService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/openapi.yaml} — filters ON, same pattern as
 * {@link ar.scraper.web.AuthEndpointsMeTest}: a real {@link SecurityConfig}
 * and {@link JwtAuthFilter} with a real signed token, so the
 * {@link ar.scraper.security.ApiRoutePolicy} ADMIN row is what's proven.
 * {@link TokenService} must be imported too — {@code @WebMvcTest} registers
 * {@link jakarta.servlet.Filter}s but not ordinary {@code @Component}s.
 */
@WebMvcTest(controllers = OpenApiDocumentController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, TokenService.class})
@TestPropertySource(properties = {
        "auth.jwt.secret=un-secreto-de-al-menos-32-bytes-para-hs256",
        "app.cors.allowed-origins=http://localhost:5173"
})
@Epic("Security")
@Feature("Contract documentation")
@Story("GET /api/openapi.yaml — ADMIN-only, identical across install paths")
@DisplayName("OpenApiDocumentController — GET /api/openapi.yaml")
class OpenApiDocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokens;

    @MockBean
    private UsuarioRepository usuarios;

    private String tokenDe(String rol) {
        UUID id = UUID.randomUUID();
        when(usuarios.autorizacionDe(id)).thenReturn(Optional.of(
                new UsuarioRepository.Autorizacion("u-" + rol.toLowerCase(), List.of(rol), null)));
        return tokens.emitir(id);
    }

    @Test
    @DisplayName("an ADMIN receives 200 with the YAML body")
    void adminReceivesTheDocument() throws Exception {
        mockMvc.perform(get("/api/openapi.yaml")
                        .header("Authorization", "Bearer " + tokenDe("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/yaml"))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    if (body == null || body.isBlank()) {
                        throw new AssertionError("the document body must never be empty");
                    }
                    if (!body.contains("openapi:")) {
                        throw new AssertionError("the document body does not look like an OpenAPI contract");
                    }
                });
    }

    @Test
    @DisplayName("a VIEWER is refused at the policy layer, not by the controller")
    void viewerIsForbidden() throws Exception {
        mockMvc.perform(get("/api/openapi.yaml")
                        .header("Authorization", "Bearer " + tokenDe("VIEWER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an anonymous request is unauthorized")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/openapi.yaml")).andExpect(status().isUnauthorized());
    }
}
