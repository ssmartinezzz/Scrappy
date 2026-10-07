package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.Page;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Scraping")
@Feature("Vaypol")
@DisplayName("VaypolPage — reads the listing's __NEXT_DATA__ in its 2026-10 shape")
class VaypolPageNextDataTest {

    private static final String BASE = "https://www.vaypol.com.ar";

    // Trimmed from a real /productos/p/1 response (2026-10-07). The page renders 12 cards; the
    // payload carries 60, so a parser that misses this shape loses 4 of every 5 products and
    // makes every one of the rest cost a product-page fetch for its image.
    private static List<Product> parsear() throws Exception {
        String json = Files.readString(Path.of(
                VaypolPageNextDataTest.class.getResource("/fixtures/vaypol/next-data-listado.json").toURI()));
        VaypolPage page = new VaypolPage(Mockito.mock(Page.class), 1000, "Vaypol", BASE, 0, 5_000_000);
        return page.productosDeNextData(json, BASE);
    }

    @Test
    @DisplayName("every product in the payload comes out, with its image")
    void everyProductWithImage() throws Exception {
        List<Product> productos = parsear();

        assertThat(productos).hasSize(2);
        assertThat(productos).allSatisfy(p ->
                assertThat(p.imagenUrl()).startsWith("https://production.cdn.vaypol.com/variants/"));
    }

    @Test
    @DisplayName("the URL is the card's /<slug>-<id>, without the variant suffix — it is the upsert key")
    void urlMatchesTheCardHref() throws Exception {
        assertThat(parsear()).extracting(Product::url).containsExactly(
                BASE + "/zapatillas-adidas-adistar-control-3-34667",
                BASE + "/zapatillas-nike-terra-manta-34470");
    }

    @Test
    @DisplayName("price is sale_price when there is one, with original as the list price")
    void pricesFromAllPrices() throws Exception {
        List<Product> productos = parsear();

        assertThat(productos.get(0).precio()).isEqualTo(199999.0);
        assertThat(productos.get(0).precioOriginal()).isNull();
        assertThat(productos.get(1).precio()).isEqualTo(116999.0);
        assertThat(productos.get(1).precioOriginal()).isEqualTo(179999.0);
    }
}
