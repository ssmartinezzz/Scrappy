package ar.scraper.web.api;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.JwtAuthFilter;
import ar.scraper.security.SecurityConfig;
import ar.scraper.security.TokenService;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The 401/403 the security chain writes itself (before any controller runs) uses the same
 * {@code {"error":{code,message}}} body as a handler-raised error. Filters ON, real SecurityConfig.
 */
@WebMvcTest(controllers = {SecurityErrorBodyTest.Rutas.class, ApiErrorController.class})
@Import({SecurityConfig.class, JwtAuthFilter.class, TokenService.class, SecurityErrorBodyTest.Rutas.class})
@TestPropertySource(properties = {
        "auth.jwt.secret=un-secreto-de-al-menos-32-bytes-para-hs256",
        "app.cors.allowed-origins=http://localhost:5173"
})
@Epic("API envelope")
@Feature("Security errors")
@DisplayName("SecurityConfig — 401/403 bodies")
class SecurityErrorBodyTest {

    @RestController
    static class Rutas {
        @GetMapping("/api/status")    String status()   { return "{}"; }
        @GetMapping("/api/db/export") String dbExport() { return "{}"; }
        @GetMapping("/api/sin-regla") String sinRegla() { return "{}"; }
    }

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
    @DisplayName("no credential: 401 with the no_autenticado envelope")
    void anonymousGets401Envelope() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error.code").value("no_autenticado"))
                .andExpect(jsonPath("$.error.message").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("a token that does not verify is the same 401 envelope")
    void garbageTokenGets401Envelope() throws Exception {
        mockMvc.perform(get("/api/status").header("Authorization", "Bearer no-es-un-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("no_autenticado"));
    }

    @Test
    @DisplayName("a VIEWER on an ADMIN route: 403 with the sin_permiso envelope")
    void viewerOnAdminRouteGets403Envelope() throws Exception {
        mockMvc.perform(get("/api/db/export").header("Authorization", "Bearer " + tokenDe("VIEWER")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error.code").value("sin_permiso"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }

    @Test
    @DisplayName("a route with no rule is denied with the same 403 envelope, even for an ADMIN")
    void denyAllGets403Envelope() throws Exception {
        mockMvc.perform(get("/api/sin-regla").header("Authorization", "Bearer " + tokenDe("ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("sin_permiso"));
    }

    @Test
    @DisplayName("the container's ERROR dispatch to /error is permitted without a credential and answers the envelope")
    void errorDispatchIsPermittedAndEnveloped() throws Exception {
        mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ERROR);
                            return request;
                        }))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("no_encontrado"));
    }
}
