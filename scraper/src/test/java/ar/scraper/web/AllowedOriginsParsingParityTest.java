package ar.scraper.web;

import ar.scraper.config.AllowedOrigins;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * frontend-auth-ui, Phase 1 (design threat matrix — "Allow-list drift between
 * CORS and the bootstrap check") — pins that {@link CorsConfig} and
 * {@link AllowedOrigins} parse {@code app.cors.allowed-origins} identically.
 *
 * <p>The two cannot share a bean ({@code CorsConfigTest} and
 * {@code CorsCredentialsTest.Estructural} pin {@code CorsConfig}'s no-arg
 * constructor and its {@code String allowedOrigins} field — see that class's
 * javadoc) but they do share {@link AllowedOrigins#parsear}. This test is what
 * makes that sharing load-bearing rather than aspirational: if a future change
 * edits {@code CorsConfig.origenes()} to call something other than
 * {@code AllowedOrigins.parsear} — reintroducing a second, independent copy —
 * this fails the moment the two disagree on an awkward input, rather than
 * waiting for a normalisation mismatch to become a live CSRF gap.</p>
 */
@Epic("REST API")
@Feature("CORS configuration")
@Story("CorsConfig and AllowedOrigins parse the same origin list identically")
@DisplayName("CorsConfig / AllowedOrigins — parsing parity")
class AllowedOriginsParsingParityTest {

    /** {@code getCorsConfigurations()} is protected; a subclass is the cheapest way to read it. */
    private static final class RegistroVisible extends CorsRegistry {
        Map<String, CorsConfiguration> configuraciones() {
            return getCorsConfigurations();
        }
    }

    @ParameterizedTest(name = "raw = [{0}]")
    @ValueSource(strings = {
            "http://localhost:5173",
            "  http://localhost:5173 , http://localhost:8080  ",
            "http://localhost:5173,,http://localhost:8080",
            ",http://localhost:5173,",
            "http://localhost:5173,   ,https://app.example.com",
    })
    @DisplayName("CorsConfig's registered origins equal AllowedOrigins.parsear(raw), for the same raw input")
    void corsConfigAndAllowedOriginsAgree(String raw) {
        List<String> esperado = AllowedOrigins.parsear(raw);

        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", raw);
        RegistroVisible registro = new RegistroVisible();
        config.addCorsMappings(registro);

        List<String> obtenido = registro.configuraciones().get("/**").getAllowedOrigins();

        assertThat(obtenido)
                .as("CorsConfig and AllowedOrigins must never silently disagree on the same raw value — "
                        + "one drives credentialed CORS, the other decides whether a nonce-less "
                        + "refresh is admitted (frontend-auth-ui Phase 2)")
                .containsExactlyElementsOf(esperado);
    }

    @Test
    @DisplayName("a raw value with no comma at all still parses identically")
    void singleEntryNoCommaAgrees() {
        String raw = "http://localhost:8080";

        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOrigins", raw);
        RegistroVisible registro = new RegistroVisible();
        config.addCorsMappings(registro);

        assertThat(registro.configuraciones().get("/**").getAllowedOrigins())
                .containsExactlyElementsOf(AllowedOrigins.parsear(raw))
                .containsExactly("http://localhost:8080");
    }
}
