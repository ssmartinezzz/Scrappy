package ar.scraper.web;

import ar.scraper.db.UsuarioRepository;
import ar.scraper.security.PasswordHasher;
import ar.scraper.security.RefreshCookie;
import ar.scraper.security.RefreshTokenService;
import ar.scraper.security.TokenService;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * user-accounts-and-roles, slice 4 — credentialed CORS, scoped to one path.
 *
 * <p>Credentials are granted for {@code /api/auth/refresh} and nowhere else,
 * because that is the only endpoint carrying an ambient credential. The two
 * failure modes this pins are quiet ones: a mapping registered in the wrong
 * order never applies, and a wildcard origin blows up at request time rather
 * than at startup. Neither announces itself — the first looks like a session
 * that expires every fifteen minutes for no reason, the second like a 500 on
 * login with a stack trace pointing at CORS internals instead of at the
 * variable that is wrong.</p>
 */
@Epic("REST API")
@Feature("CORS configuration")
@Story("Credentials scoped to the refresh path only")
@DisplayName("CorsConfig — credentialed refresh path")
class CorsCredentialsTest {

    private static final String ORIGEN = "http://localhost:5173";

    @Nested
    @WebMvcTest(controllers = {RootController.class, AuthEndpoints.class})
    @Import(CorsConfig.class)
    @TestPropertySource(properties = "app.cors.allowed-origins=" + ORIGEN)
    @DisplayName("through the real filter/handler stack")
    class PorHttp {

        @Autowired
        private MockMvc mockMvc;

        @MockBean
        private UsuarioRepository usuarios;
        @MockBean
        private PasswordHasher hasher;
        @MockBean
        private TokenService tokens;
        @MockBean
        private RefreshTokenService sesiones;

        @Test
        @DisplayName("the refresh path echoes the exact origin and allows credentials")
        void refreshPathAllowsCredentials() throws Exception {
            mockMvc.perform(options(RefreshCookie.PATH)
                            .header("Origin", ORIGEN)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", ORIGEN))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        }

        @Test
        @DisplayName("every other path grants no credentials")
        void otherPathsGrantNoCredentials() throws Exception {
            mockMvc.perform(options("/api/auth/login")
                            .header("Origin", ORIGEN)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", ORIGEN))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }
    }

    @Nested
    @DisplayName("structurally, without a context")
    class Estructural {

        /** {@code getCorsConfigurations()} is protected; a subclass is the cheapest way to read it. */
        private static final class RegistroVisible extends CorsRegistry {
            Map<String, CorsConfiguration> configuraciones() {
                return getCorsConfigurations();
            }
        }

        private CorsConfig configCon(String origenes) {
            CorsConfig config = new CorsConfig();
            ReflectionTestUtils.setField(config, "allowedOrigins", origenes);
            return config;
        }

        @Test
        @DisplayName("the refresh mapping is registered before the catch-all")
        void theRefreshMappingComesFirst() {
            RegistroVisible registro = new RegistroVisible();

            configCon(ORIGEN).addCorsMappings(registro);

            List<String> patrones = List.copyOf(registro.configuraciones().keySet());
            assertThat(patrones)
                    .as("first match wins: registered after /**, the refresh rule is never consulted "
                            + "and the browser silently drops the cookie on every refresh")
                    .containsExactly(RefreshCookie.PATH, "/**");
            assertThat(registro.configuraciones().get(RefreshCookie.PATH).getAllowCredentials()).isTrue();
            assertThat(registro.configuraciones().get("/**").getAllowCredentials()).isFalse();
        }

        @Test
        @DisplayName("allowedHeaders stays '*' — valid under credentialed CORS because Spring echoes names")
        void allowedHeadersIsLeftAlone() {
            RegistroVisible registro = new RegistroVisible();

            configCon(ORIGEN).addCorsMappings(registro);

            assertThat(registro.configuraciones().get(RefreshCookie.PATH).getAllowedHeaders())
                    .containsExactly("*");
        }

        @Test
        @DisplayName("a wildcard origin fails at startup, naming the variable")
        void aWildcardOriginFailsAtStartup() {
            assertThatThrownBy(() -> configCon("*").validarOrigenes())
                    .as("left to Spring this surfaces as a 500 on login, at request time, "
                            + "pointing at CORS internals instead of at the variable")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("APP_CORS_ALLOWED_ORIGINS");
        }

        @Test
        @DisplayName("a wildcard hidden in a list is caught too")
        void aWildcardInsideAListIsCaught() {
            assertThatThrownBy(() -> configCon(ORIGEN + ",*").validarOrigenes())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("APP_CORS_ALLOWED_ORIGINS");
        }

        @Test
        @DisplayName("an empty list fails at startup too")
        void anEmptyListFailsAtStartup() {
            assertThatThrownBy(() -> configCon("   ").validarOrigenes())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("APP_CORS_ALLOWED_ORIGINS");
        }

        @Test
        @DisplayName("an explicit list starts normally")
        void anExplicitListIsAccepted() {
            assertThatCode(() -> configCon(ORIGEN + ",https://app.example.com").validarOrigenes())
                    .doesNotThrowAnyException();
        }
    }
}
