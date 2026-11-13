package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Epic("Scraping Engine")
@Feature("WooCommerce Parsing")
@Story("Stock from the card's post class")
@DisplayName("WooCommercePage — stock from instock / outofstock / onbackorder")
class WooCommercePageStockTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final WooCommercePage woo = new WooCommercePage(null, 0, "Sitio", "https://tienda.test", 0, 5_000_000);

    @SuppressWarnings("unchecked")
    private Optional<Product> fromNode(String json) throws Exception {
        Method m = WooCommercePage.class.getDeclaredMethod("fromNode", JsonNode.class);
        m.setAccessible(true);
        return (Optional<Product>) m.invoke(woo, MAPPER.readTree(json));
    }

    private static String node(String stock) {
        return "{\"nombre\":\"Notebook\",\"precio\":\"$1.000\",\"url\":\"https://tienda.test/producto/n/\""
                + (stock == null ? "" : ",\"stock\":\"" + stock + "\"") + "}";
    }

    @Test
    @DisplayName("instock is in stock, outofstock keeps the row flagged, backorder or no class is unknown")
    void stockFromTheExtractedClass() throws Exception {
        assertThat(fromNode(node("instock")).orElseThrow().enStock()).isTrue();
        assertThat(fromNode(node("outofstock")).orElseThrow().enStock()).isFalse();
        assertThat(fromNode(node("onbackorder")).orElseThrow().enStock()).isNull();
        assertThat(fromNode(node("")).orElseThrow().enStock()).isNull();
        assertThat(fromNode(node(null)).orElseThrow().enStock()).isNull();
    }

    private static String card(String slug, String classes) {
        return "<li class='product " + classes + "'><a class='woocommerce-loop-product__link' href='https://tienda.test/producto/"
                + slug + "/'><h2 class='woocommerce-loop-product__title'>Notebook " + slug + "</h2></a>"
                + "<span class='price'><span class='woocommerce-Price-amount amount'><bdi>$1.000</bdi></span></span></li>";
    }

    @Test
    @DisplayName("the extractor reads the stock class off each card")
    void extractorReadsTheStockClass() throws Exception {
        Method js = WooCommercePage.class.getDeclaredMethod("buildExtractorJs");
        js.setAccessible(true);
        try (Playwright pw = Playwright.create()) {
            Browser browser;
            try {
                browser = pw.chromium().launch(new BrowserType.LaunchOptions().setArgs(List.of("--no-sandbox")));
            } catch (Exception e) {
                assumeTrue(false, "Chromium cannot be launched here: " + e.getMessage());
                return;
            }
            try (browser; Page p = browser.newPage()) {
                p.setContent("<ul class='products'>" + card("a", "instock") + card("b", "outofstock")
                        + card("c", "onbackorder") + card("d", "type-product") + "</ul>");
                JsonNode arr = MAPPER.readTree((String) p.evaluate((String) js.invoke(woo)));

                assertThat(arr).extracting(n -> n.path("stock").asText("<absent>"))
                        .containsExactly("instock", "outofstock", "onbackorder", "");
            }
        }
    }
}
