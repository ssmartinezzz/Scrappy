package ar.scraper.scrapers;

import ar.scraper.classification.SiteRegistry;
import ar.scraper.config.ScraperConfig;
import ar.scraper.config.ScraperConfig.SiteConfig;
import ar.scraper.pages.TiendanubePage;
import ar.scraper.pages.VtexPage;
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

// The run's site name must equal sitio.nombre: sp_upsert_sitio_categoria joins on it,
// and a monomarca site's marca is that nombre.
@DisplayName("ScraperFactory - site display name")
class ScraperFactoryDisplayNameTest {

    @Test
    @DisplayName("a registered site runs under its sitio.nombre, not the capitalized key")
    void registeredSiteUsesSitioNombre() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "underarmour", new SiteRegistry.Sitio(
                        "Under Armour", "underarmour", "vtex", false, null, "config", true)));
        List<Object> sitios = new ArrayList<>();
        try (MockedConstruction<VtexPage> mc = mockConstruction(VtexPage.class,
                (mock, ctx) -> sitios.add(ctx.arguments().get(2)))) {
            ScraperFactory.crear(new ScraperConfig(),
                    new SiteConfig("underarmour", "https://www.underarmour.com.ar/", "indumentaria"), registry)
                    .scrape(mock(Page.class));
        }
        assertThat(sitios).containsExactly("Under Armour");
    }

    @Test
    @DisplayName("an unregistered site keeps the capitalized key")
    void unregisteredSiteKeepsCapitalizedKey() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of());
        List<Object> sitios = new ArrayList<>();
        try (MockedConstruction<TiendanubePage> mc = mockConstruction(TiendanubePage.class,
                (mock, ctx) -> sitios.add(ctx.arguments().get(2)))) {
            ScraperFactory.crear(new ScraperConfig(),
                    new SiteConfig("tiendaquenoexistia", "https://example.com/productos/", "indumentaria"), registry)
                    .scrape(mock(Page.class));
        }
        assertThat(sitios).containsExactly("Tiendaquenoexistia");
    }
}
