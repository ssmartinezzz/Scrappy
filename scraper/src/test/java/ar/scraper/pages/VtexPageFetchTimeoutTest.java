package ar.scraper.pages;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Nike's WAF held an API request open forever and the in-page fetch hung the whole run (2026-11-19). */
@Epic("Scraping")
@Feature("VTEX WAF fallback")
@DisplayName("VtexPage — the in-page fetch gives up on a request that never answers")
class VtexPageFetchTimeoutTest {

    private static final String ORIGEN = "https://tienda.test";

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void launchChromiumOrSkip() {
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setArgs(List.of("--no-sandbox")));
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fetchEn(Page p, String path) {
        return (Map<String, Object>) p.evaluate(VtexPage.fetchJs(500), path);
    }

    private static Page paginaEnElOrigen() {
        Page p = browser.newPage();
        p.route(ORIGEN + "/api/cuelga**", route -> { /* never answered, like the WAF */ });
        p.route(ORIGEN + "/api/ok**", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setStatus(206).setBody("[]").setHeaders(Map.of("resources", "0-0/7",
                        "access-control-expose-headers", "resources"))));
        p.route(ORIGEN + "/", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setStatus(200).setContentType("text/html").setBody("<html></html>")));
        p.navigate(ORIGEN + "/");
        return p;
    }

    @Test
    @Timeout(20)
    @DisplayName("a request that is never answered comes back as status 0 instead of hanging")
    void requestColgadoDevuelveCero() {
        try (Page p = paginaEnElOrigen()) {
            Map<String, Object> r = fetchEn(p, "/api/cuelga");

            assertThat(((Number) r.get("status")).intValue()).isZero();
            assertThat(r.get("body")).isEqualTo("");
        }
    }

    @Test
    @Timeout(20)
    @DisplayName("an answered request still returns its status, body and resources header")
    void requestRespondidoNoCambia() {
        try (Page p = paginaEnElOrigen()) {
            Map<String, Object> r = fetchEn(p, "/api/ok");

            assertThat(((Number) r.get("status")).intValue()).isEqualTo(206);
            assertThat(r.get("body")).isEqualTo("[]");
            assertThat(r.get("resources")).isEqualTo("0-0/7");
        }
    }
}
