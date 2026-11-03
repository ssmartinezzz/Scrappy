package ar.scraper.pages;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Flatsome renders WooCommerce cards as div.type-product, not li.product (fixture: notebookspro). */
@Epic("Scraping Engine")
@Feature("WooCommerce Parsing")
@Story("Flatsome theme listing")
@DisplayName("WooCommercePage — reads Flatsome div.type-product cards")
class WooCommerceFlatsomeListingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void launchChromiumOrSkip() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setArgs(java.util.List.of("--no-sandbox")));
        } catch (Exception | LinkageError e) {
            if (playwright != null) playwright.close();
            playwright = null;
            assumeTrue(false, "Chromium cannot be launched here: " + e.getMessage());
        }
    }

    @AfterAll
    static void close() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    private static JsonNode extraer(String fixture) throws Exception {
        String html;
        try (InputStream in = WooCommerceFlatsomeListingTest.class.getResourceAsStream(fixture)) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        WooCommercePage woo = new WooCommercePage(null, 0, "notebookspro", "https://notebookspro.ar", 0, 1);
        Method m = WooCommercePage.class.getDeclaredMethod("buildExtractorJs");
        m.setAccessible(true);
        try (Page p = browser.newPage()) {
            p.setContent(html);
            return MAPPER.readTree((String) p.evaluate((String) m.invoke(woo)));
        }
    }

    @Test
    @DisplayName("each Flatsome card yields one product with sale price, list price and the lazy image")
    void flatsomeCardsAreRead() throws Exception {
        JsonNode arr = extraer("/fixtures/notebookspro/tienda.html");

        assertThat(arr).hasSize(2);
        JsonNode first = arr.get(0);
        assertThat(first.path("nombre").asText()).startsWith("CYMAX 2 NUBE LITE");
        assertThat(first.path("url").asText())
                .startsWith("https://notebookspro.ar/producto/cymax-2-nube-lite-");
        assertThat(first.path("precio").asText()).contains("680.000");
        assertThat(first.path("precioOrig").asText()).contains("800.000");
        assertThat(first.path("img").asText())
                .isEqualTo("https://notebookspro.ar/wp-content/uploads/2026/10/CYMAX-NUBE-LITE-5-500x500.webp");
    }

    private static String card(String slug) {
        return "<div class='product-small col type-product'><p class='name product-title woocommerce-loop-product__title'>"
                + "<a href='https://tienda.test/producto/" + slug + "/'>Notebook " + slug + "</a></p>"
                + "<span class='price'><span class='woocommerce-Price-amount amount'><bdi>$1.000</bdi></span></span></div>";
    }

    @Test
    @DisplayName("a static /tienda/ page with a product shortcode loses to the real shop archive")
    void prefersTheShopArchiveOverAStaticPageWithProducts() {
        // notebookspro: /tienda/ is a WordPress page (page-id-5137) showing 8 fixed products with no
        // pagination; the shop archive (body.post-type-archive-product) is /productos/.
        try (Page p = browser.newPage()) {
            p.route("https://tienda.test/**", route -> {
                String url = route.request().url();
                String body;
                if (url.endsWith("/tienda/")) {
                    body = "<html><body class='page-id-5137'>" + card("fija-1") + card("fija-2") + "</body></html>";
                } else if (url.endsWith("/productos/")) {
                    body = "<html><body class='post-type-archive-product woocommerce-shop'>"
                            + card("a") + card("b") + card("c") + "</body></html>";
                } else {
                    route.fulfill(new com.microsoft.playwright.Route.FulfillOptions().setStatus(404).setBody("not found"));
                    return;
                }
                route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                        .setStatus(200).setContentType("text/html").setBody(body));
            });
            WooCommercePage woo = new WooCommercePage(p, 5000, "notebookspro", "https://tienda.test", 0, 5_000_000);

            java.util.List<ar.scraper.model.Product> productos = woo.scrapeAll();

            assertThat(productos).extracting(ar.scraper.model.Product::url).containsExactly(
                    "https://tienda.test/producto/a/", "https://tienda.test/producto/b/", "https://tienda.test/producto/c/");
            assertThat(woo.truncamiento()).isNull();
        }
    }
}
