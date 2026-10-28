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

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Epic("Scraping Engine")
@Feature("TiendaNube Parsing")
@Story("DOM gender detection")
@DisplayName("TiendanubePage — gender words match whole words in the DOM fallback")
class TiendanubeGeneroDomTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VARIANTS =
            "<script type='application/json'>{\"price_with_payment_discount\":100,\"url\":\"/manga-larga\"}</script>";

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

    private String generoDe(String nombre, String extra) throws Exception {
        TiendanubePage tn = new TiendanubePage(null, 0, "x", "https://x.com/productos/", 0, 1);
        Method m = TiendanubePage.class.getDeclaredMethod("buildExtractorJs");
        m.setAccessible(true);
        try (Page p = browser.newPage()) {
            p.setContent("<div data-product-id='1' data-price='9000'><a href='/productos/a' title='" + nombre
                    + "'>" + nombre + "</a>" + extra + "</div>");
            JsonNode arr = MAPPER.readTree((String) p.evaluate((String) m.invoke(tn)));
            assertThat(arr).hasSize(1);
            return arr.get(0).path("genero").asText();
        }
    }

    @Test
    @DisplayName("price_with_payment_discount and manga are not men / man")
    void substringsInsideOtherWordsGiveNoGender() throws Exception {
        assertThat(generoDe("Never Give Up", VARIANTS + "<span>Manga larga, semana 3</span>")).isEmpty();
    }

    @Test
    @DisplayName("a real gender word still tags the card")
    void realWordsStillMatch() throws Exception {
        assertThat(generoDe("Remera Hombre", VARIANTS)).isEqualTo("hombre");
        assertThat(generoDe("Calza Mujer", VARIANTS)).isEqualTo("mujer");
        assertThat(generoDe("Remera Men", "")).isEqualTo("hombre");
        assertThat(generoDe("Buzo hombre y mujer", "")).isEqualTo("unisex");
    }
}
