package ar.scraper.config;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code rubro} field on {@link ScraperConfig.SiteConfig}.
 * Uses the package-private {@link ScraperConfig#ScraperConfig(Properties)} test
 * seam to inject minimal in-memory properties without loading config.properties
 * from the classpath. No Spring context required.
 */
@Epic("Configuration")
@Feature("Rubro Config")
@DisplayName("ScraperConfig — rubro resolution per site")
class ScraperConfigRubroTest {

    @Step("Load ScraperConfig from in-memory properties")
    private ScraperConfig configWith(Properties p) {
        return new ScraperConfig(p);
    }

    @Step("Build site properties: sitio={nombre}, rubro={rubro}")
    private Properties siteProps(String nombre, String rubro) {
        Properties p = new Properties();
        p.setProperty("sitio." + nombre + ".url", "https://example.com/productos/");
        p.setProperty("sitio." + nombre + ".activo", "true");
        if (rubro != null) {
            p.setProperty("sitio." + nombre + ".rubro", rubro);
        }
        return p;
    }

    @Test
    void readsTecnologiaRubroForMaximus() {
        Properties p = siteProps("maximus", "tecnologia");
        List<ScraperConfig.SiteConfig> sites = configWith(p).getSitiosActivos();
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).rubro()).isEqualTo("tecnologia");
    }

    @Test
    void readsTecnologiaRubroForFullh4rd() {
        Properties p = siteProps("fullh4rd", "tecnologia");
        List<ScraperConfig.SiteConfig> sites = configWith(p).getSitiosActivos();
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).rubro()).isEqualTo("tecnologia");
    }

    @Test
    void readsTecnologiaRubroForCompragamer() {
        Properties p = siteProps("compragamer", "tecnologia");
        List<ScraperConfig.SiteConfig> sites = configWith(p).getSitiosActivos();
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).rubro()).isEqualTo("tecnologia");
    }

    @Test
    void readsSupplementosRubroForEntreno() {
        Properties p = siteProps("entreno", "suplementos");
        List<ScraperConfig.SiteConfig> sites = configWith(p).getSitiosActivos();
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).rubro()).isEqualTo("suplementos");
    }

    @Test
    void defaultsToIndumentariaWhenNoRubroKey() {
        Properties p = siteProps("barnes", null);  // no rubro key
        List<ScraperConfig.SiteConfig> sites = configWith(p).getSitiosActivos();
        assertThat(sites).hasSize(1);
        assertThat(sites.get(0).rubro()).isEqualTo("indumentaria");
    }

    @Test
    void inactiveSitesAreExcluded() {
        Properties p = new Properties();
        p.setProperty("sitio.inactive.url", "https://example.com/productos/");
        p.setProperty("sitio.inactive.activo", "false");
        p.setProperty("sitio.inactive.rubro", "tecnologia");

        List<ScraperConfig.SiteConfig> sites = configWith(p).getSitiosActivos();
        assertThat(sites).isEmpty();
    }
}
