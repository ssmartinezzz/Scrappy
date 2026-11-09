package ar.scraper.scrapers;

import ar.scraper.classification.SiteRegistry;
import ar.scraper.config.ScraperConfig;
import ar.scraper.config.ScraperConfig.SiteConfig;
import ar.scraper.pages.HadriaticaPage;
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

@DisplayName("ScraperFactory - hadriatica")
class HadriaticaRoutingTest {

    private static final String BASE = "https://www.hadriatica.com/productos/";

    @Test
    @DisplayName("la plataforma hadriatica rutea a HadriaticaPage y no a tiendanube")
    void rutea() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "hadriatica", new SiteRegistry.Sitio(
                        "Hadriatica", "hadriatica", "hadriatica", false, null, "config")));
        List<Object> construidas = new ArrayList<>();
        try (MockedConstruction<HadriaticaPage> mc = mockConstruction(HadriaticaPage.class,
                (mock, ctx) -> construidas.add(mock))) {
            ScraperFactory.crear(new ScraperConfig(), new SiteConfig("hadriatica", BASE, "juguetes"), registry)
                    .scrape(mock(Page.class));
        }
        assertThat(construidas).hasSize(1);
    }
}
