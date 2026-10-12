package ar.scraper.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** Usa el seam {@link ScraperConfig#ScraperConfig(Properties)}: sin contexto de Spring. */
@Epic("Configuration")
@Feature("FlareSolverr Config")
@DisplayName("ScraperConfig — Cloudflare bypass (FlareSolverr) config")
class ScraperConfigFlareSolverrTest {

    @Step("Load ScraperConfig from in-memory properties")
    private ScraperConfig configWith(Properties p) {
        return new ScraperConfig(p);
    }

    @Test
    @DisplayName("URL por defecto vacía cuando no está configurada")
    void defaultFlareSolverrUrlIsBlankWhenNotConfigured() {
        ScraperConfig config = configWith(new Properties());
        assertThat(config.getFlareSolverrUrl()).isEmpty();
    }

    @Test
    @DisplayName("Lee scraper.flaresolverr.url de las properties")
    void readsFlareSolverrUrlFromProperties() {
        Properties p = new Properties();
        p.setProperty("scraper.flaresolverr.url", "http://localhost:8191");
        assertThat(configWith(p).getFlareSolverrUrl()).isEqualTo("http://localhost:8191");
    }

    @Test
    @DisplayName("El override de entorno gana sobre la property")
    void envOverridesPropertyUrl() {
        Properties p = new Properties();
        p.setProperty("scraper.flaresolverr.url", "http://localhost:8191");
        assertThat(configWith(p).getFlareSolverrUrl("http://flare-solverr:8191"))
                .isEqualTo("http://flare-solverr:8191");
    }

    @Test
    @DisplayName("Env en blanco cae a la property")
    void envBlankFallsBackToPropertyUrl() {
        Properties p = new Properties();
        p.setProperty("scraper.flaresolverr.url", "http://localhost:8191");
        assertThat(configWith(p).getFlareSolverrUrl("")).isEqualTo("http://localhost:8191");
    }

    @Test
    @DisplayName("sitio.<nombre>.cloudflare=true activa el bypass")
    void siteRequiresCloudflareBypassWhenFlagSet() {
        Properties p = new Properties();
        p.setProperty("sitio.fullh4rd.cloudflare", "true");
        assertThat(configWith(p).requiereBypassCloudflare("fullh4rd")).isTrue();
    }

    @Test
    @DisplayName("Sin flag el sitio no requiere bypass")
    void siteDoesNotRequireBypassByDefault() {
        ScraperConfig config = configWith(new Properties());
        assertThat(config.requiereBypassCloudflare("fullh4rd")).isFalse();
        assertThat(config.requiereBypassCloudflare("compragamer")).isFalse();
    }
}