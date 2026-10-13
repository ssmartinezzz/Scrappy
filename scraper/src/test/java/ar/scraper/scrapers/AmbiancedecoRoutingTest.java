package ar.scraper.scrapers;

import ar.scraper.classification.SiteRegistry;
import ar.scraper.config.ScraperConfig;
import ar.scraper.config.ScraperConfig.SiteConfig;
import ar.scraper.pages.AmbiancedecoPage;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;

@DisplayName("ScraperFactory - ambiancedeco")
class AmbiancedecoRoutingTest {

    private static final String BASE = "https://www.ambiancedeco.com.ar/productos/";

    @Test
    @DisplayName("la plataforma ambiancedeco rutea a AmbiancedecoPage y no a tiendanube")
    void rutea() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "ambiancedeco", new SiteRegistry.Sitio(
                        "Ambiancedeco", "ambiancedeco", "ambiancedeco", false, "hogar", "config")));
        List<Object> construidas = new ArrayList<>();
        try (MockedConstruction<AmbiancedecoPage> mc = mockConstruction(AmbiancedecoPage.class,
                (mock, ctx) -> construidas.add(mock))) {
            ScraperFactory.crear(new ScraperConfig(), new SiteConfig("ambiancedeco", BASE, "hogar"), registry)
                    .scrape(mock(Page.class));
        }
        assertThat(construidas).hasSize(1);
    }
}
