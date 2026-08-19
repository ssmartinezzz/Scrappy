package ar.scraper.web;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.PasswordHasher;
import ar.scraper.security.TokenService;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * user-accounts-and-roles, slice 2 — proves the route exists.
 *
 * <p>{@link AuthEndpointsLoginTest} covers what login <i>does</i>, against a
 * real database and a real hash, by calling the endpoint directly. That leaves
 * one thing unproven: that the method is actually reachable at
 * {@code POST /api/auth/login}. A mapping typo would sail past every assertion
 * there and only surface when a client tried to log in.</p>
 *
 * <p>This test loads the web layer alone — no {@code InflacionService} fetching
 * INDEC at context refresh, no Flyway, no database — and asserts the route and
 * its method. The collaborators are mocked precisely because their behaviour is
 * not what is under test here.</p>
 */
@WebMvcTest(controllers = AuthEndpoints.class)
@Epic("Security")
@Feature("Login")
@Story("POST /api/auth/login is actually mapped")
@DisplayName("AuthEndpoints — HTTP mapping")
class AuthEndpointsMappingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UsuarioRepository usuarios;

    @MockBean
    private PasswordHasher hasher;

    @MockBean
    private TokenService tokens;

    @Test
    @DisplayName("POST /api/auth/login is routed — bad credentials reach the endpoint and get 401")
    void loginIsMappedAndReturnsUnauthorizedForBadCredentials() throws Exception {
        when(usuarios.buscarActivaPorUsername(anyString())).thenReturn(Optional.empty());
        when(hasher.verify(any(), any())).thenReturn(false);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ana\",\"password\":\"mal\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/auth/login is not routed — the mapping is method-scoped")
    void loginIsPostOnly() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed());
    }
}
