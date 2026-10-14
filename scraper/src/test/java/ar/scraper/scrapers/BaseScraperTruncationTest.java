package ar.scraper.scrapers;

import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.pages.CatalogPage;
import com.microsoft.playwright.Page;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Epic("Scraping Engine")
@Feature("Partial catalogs")
@DisplayName("BaseScraper — a page that cut its pagination yields products plus an error")
class BaseScraperTruncationTest {

    private static final Product PRODUCTO = Product.builder()
            .sitio("Vaypol").nombre("Zapatilla").precio(1000).url("https://vaypol.test/z").build();

    private static BaseScraper scraper(CatalogPage catalogo) {
        return new BaseScraper(new ScraperConfig(), "Vaypol", "https://vaypol.test", List.of(),
                (page, ctx) -> catalogo);
    }

    @Test
    @DisplayName("the cut travels as the result's error, with the products kept")
    void cutTravelsAsError() {
        CatalogPage cortada = new CatalogPage() {
            public List<Product> scrapeAll() { return List.of(PRODUCTO); }
            public String truncamiento() { return "paginación cortada en p59: net::ERR_NETWORK_CHANGED"; }
        };

        var resultado = scraper(cortada).resultado(mock(Page.class), System.currentTimeMillis());

        assertThat(resultado.productos()).containsExactly(PRODUCTO);
        assertThat(resultado.error()).isEqualTo("paginación cortada en p59: net::ERR_NETWORK_CHANGED");
    }

    @Test
    @DisplayName("a catalog read to its end has no error")
    void completeCatalogHasNoError() {
        CatalogPage completa = () -> List.of(PRODUCTO);

        var resultado = scraper(completa).resultado(mock(Page.class), System.currentTimeMillis());

        assertThat(resultado.productos()).containsExactly(PRODUCTO);
        assertThat(resultado.error()).isNull();
    }
}
