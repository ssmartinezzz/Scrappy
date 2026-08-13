package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.Page;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * fix-zero-yield-tech-sites, C5 (spec: {@code tech-site-catalog-coverage} /
 * Venex Scraped Through an osCommerce Reader With Leaf-Category Pagination,
 * design D5/D6).
 */
@Epic("Scraping Engine")
@Feature("Tech stores")
@Story("Venex reads osCommerce leaf-category listings, not the landing page")
@DisplayName("OsCommercePage — Venex/osCommerce category listing")
class OsCommercePageListingTest {

    private static final String BASE_URL = "https://www.venex.com.ar";
    private static final double MIN = 0;
    private static final double MAX = 100_000_000;

    private static final String LEAF_PAGE1 = readFixture("leaf-page1.html");
    private static final String LEAF_PAGE2 = readFixture("leaf-page2.html");
    private static final String LEAF_PAGE3_EMPTY = readFixture("leaf-page3-empty.html");
    private static final String LANDING = readFixture("landing-componentes-de-pc.html");
    private static final String NAV = readFixture("nav.html");

    @Test
    @DisplayName("una pagina leaf de 12 items rinde 12 productos")
    void parsesTwelveItemsFromOnePage() {
        List<Product> result = OsCommercePage.parseListing(
                LEAF_PAGE1, "Venex", BASE_URL, "Placas de Video", MIN, MAX);
        assertThat(result).hasSize(12);
    }

    @Test
    @DisplayName("regresion: la fixture ya no rinde 0 (Venex nunca estuvo registrado)")
    void regressionYieldsMoreThanZero() {
        List<Product> result = OsCommercePage.parseListing(
                LEAF_PAGE1, "Venex", BASE_URL, "Placas de Video", MIN, MAX);
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("parsea tambien la forma que sirve page.content() en produccion: "
            + "onclick con comillas dobles y el JSON con &quot; en vez de comillas simples + JSON crudo")
    void parsesChromiumSerializedAttributeForm() {
        // BasePage.navigateTo() + page.content() en producción devuelve el DOM
        // SERIALIZADO por Chromium, no el HTML crudo que sirve el servidor: los
        // atributos se normalizan a comillas dobles y las comillas internas del
        // JSON de onclick se escapan como entidad &quot;. Medido en vivo contra
        // venex.com.ar (2026-08-13): un parser que sólo reconoce
        // onclick='enhancedClick({"id":...})' (comilla simple, como sirve el
        // curl crudo) rinde 0 productos contra la página real vista por
        // Playwright — este es el caso que lo prueba.
        String chromiumSerializedCard = """
                <div class="product-box" style="">
                    <div class="product-box-media">
                        <a href="https://www.venex.com.ar/componentes-de-pc/placas-de-video/soporte-para-gpu-cooler-master-atlas-argb-vga-bracket.html" onclick="enhancedClick({&quot;id&quot;:&quot;18549&quot;,&quot;name&quot;:&quot;Soporte para GPU Cooler Master Atlas ARGB VGA Bracket&quot;,&quot;category&quot;:&quot;Placas de Video&quot;,&quot;brand&quot;:&quot;COOLER MASTER&quot;,&quot;price&quot;:&quot;99990&quot;,&quot;list&quot;:&quot;Listado de Productos&quot;,&quot;position&quot;:&quot;1&quot;})">
                            <img src="products_images/thumb/1727970464_1.jpg">
                        </a>
                    </div>
                </div>
                """;

        List<Product> result = OsCommercePage.parseListing(
                chromiumSerializedCard, "Venex", BASE_URL, "Placas de Video", MIN, MAX);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nombre()).isEqualTo("Soporte para GPU Cooler Master Atlas ARGB VGA Bracket");
        assertThat(result.get(0).precio()).isEqualTo(99990.0);
    }

    @Test
    @DisplayName("precio fuera de banda excluye el item")
    void excludesOutOfPriceBoundItems() {
        List<Product> result = OsCommercePage.parseListing(
                LEAF_PAGE1, "Venex", BASE_URL, "Placas de Video", 200_000, 1_000_000);
        assertThat(result).hasSize(11); // excluye el soporte de $99.990
        assertThat(result.stream().map(Product::nombre))
                .noneMatch(n -> n.contains("Soporte para GPU"));
    }

    @Test
    @DisplayName("nombre, precio, categoria e imagen se mapean desde el JSON de enhancedClick")
    void mapsFieldsFromEnhancedClickJson() {
        List<Product> result = OsCommercePage.parseListing(
                LEAF_PAGE1, "Venex", BASE_URL, "Placas de Video", MIN, MAX);
        Product soporte = result.stream()
                .filter(p -> p.url().contains("18549"))
                .findFirst().orElseThrow();

        assertThat(soporte.nombre()).isEqualTo("Soporte para GPU Cooler Master Atlas ARGB VGA Bracket");
        assertThat(soporte.precio()).isEqualTo(99990.0);
        assertThat(soporte.categoria()).isEqualTo("Placas de Video");
        assertThat(soporte.imagenUrl()).contains("18549_1.jpg");
        assertThat(soporte.url()).isEqualTo(
                "https://www.venex.com.ar/componentes-de-pc/placas-de-video/soporte-para-gpu-cooler-master-atlas-argb-vga-bracket-18549.html");
    }

    @Test
    @DisplayName("precioOriginal es siempre null (Venex no tiene senal de precio tachado en el listado)")
    void precioOriginalIsAlwaysNull() {
        List<Product> result = OsCommercePage.parseListing(
                LEAF_PAGE1, "Venex", BASE_URL, "Placas de Video", MIN, MAX);
        assertThat(result).allSatisfy(p -> assertThat(p.precioOriginal()).isNull());
    }

    @Test
    @DisplayName("descubre slugs de categoria de primer nivel desde el nav, excluye carrito/mi-cuenta")
    void extractsTopCategorySlugs() {
        List<String> slugs = OsCommercePage.extractTopCategorySlugs(NAV);

        assertThat(slugs).contains("componentes-de-pc", "perifericos", "monitores", "notebooks",
                "pc-de-escritorio", "conectividad-y-redes", "almacenamiento-portatil",
                "sillas-gamers", "accesorios", "tablets");
        assertThat(slugs).doesNotContain("carrito", "mi-cuenta");
    }

    @Test
    @DisplayName("descubre slugs de categoria leaf desde la landing, nunca el slug del top mismo")
    void extractsLeafCategorySlugsFromLanding() {
        List<String> leaves = OsCommercePage.extractLeafCategorySlugs(LANDING, "componentes-de-pc");

        assertThat(leaves).isNotEmpty();
        assertThat(leaves).contains("placas-de-video", "microprocesadores", "fuentes", "gabinetes");
        assertThat(leaves).doesNotContain("componentes-de-pc");
    }

    @Test
    @DisplayName("crawl multi-pagina: 12+6+0=18 total, se detiene en la pagina vacia, nunca pide la 4ta")
    void crawlAccumulatesAcrossPagesAndStopsAtEmptyPage() {
        Page mockPage = Mockito.mock(Page.class);
        when(mockPage.content()).thenReturn(LEAF_PAGE1, LEAF_PAGE2, LEAF_PAGE3_EMPTY);

        OsCommercePage osCommercePage = new OsCommercePage(
                mockPage, 5000, "Venex", BASE_URL, MIN, MAX);
        List<Product> result = osCommercePage.crawlLeafCategory(
                BASE_URL + "/componentes-de-pc/placas-de-video", "Placas de Video");

        assertThat(result).hasSize(18);
        verify(mockPage, times(3)).navigate(anyString(), any());
    }

    @Test
    @DisplayName("crawl multi-pagina: se detiene tambien si una pagina repite productos ya vistos")
    void crawlStopsOnRepeatedPageEvenWithoutAnEmptyPage() {
        Page mockPage = Mockito.mock(Page.class);
        // Venex, medido en vivo, NUNCA devuelve una pagina vacia pasado el final real:
        // repite el contenido de la ultima pagina indefinidamente.
        when(mockPage.content()).thenReturn(LEAF_PAGE1, LEAF_PAGE1, LEAF_PAGE1);

        OsCommercePage osCommercePage = new OsCommercePage(
                mockPage, 5000, "Venex", BASE_URL, MIN, MAX);
        List<Product> result = osCommercePage.crawlLeafCategory(
                BASE_URL + "/componentes-de-pc/placas-de-video", "Placas de Video");

        assertThat(result).hasSize(12);
        verify(mockPage, times(2)).navigate(anyString(), any());
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static String readFixture(String name) {
        String path = "/fixtures/venex/" + name;
        try (InputStream in = OsCommercePageListingTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
