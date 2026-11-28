package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Epic("Scraping")
@Feature("Partial catalogs")
@Story("A blocked site is an error, not an empty catalog")
@DisplayName("Navigation status — 403 and 429 surface as a cut with the HTTP status")
class HttpStatusSurfacesAsErrorTest {

    private static final String BASE = "https://tienda.test";
    private static final double MAX = 5_000_000;
    private static final String UN_PRODUCTO =
            "{\"products\":[{\"title\":\"Remera\",\"handle\":\"r\",\"variants\":[{\"price\":\"1000\"}]}]}";

    private static Response respuesta(int status, String retryAfter) {
        Response r = mock(Response.class);
        when(r.status()).thenReturn(status);
        when(r.headers()).thenReturn(retryAfter == null ? Map.of() : Map.of("retry-after", retryAfter));
        return r;
    }

    /** Answers the given statuses in order, repeating the last one. */
    private static Page conRespuestas(Response... en) {
        Page page = mock(Page.class);
        int[] i = {0};
        when(page.navigate(anyString(), any(Page.NavigateOptions.class)))
                .thenAnswer(inv -> en[Math.min(i[0]++, en.length - 1)]);
        return page;
    }

    private static List<Long> esperas(Page page) {
        ArgumentCaptor<Double> c = ArgumentCaptor.forClass(Double.class);
        verify(page, atLeast(0)).waitForTimeout(c.capture());
        return c.getAllValues().stream().map(Double::longValue).toList();
    }

    // ── Shopify ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Shopify 403: no products and the cut reads HTTP 403")
    void shopify403() {
        Page page = conRespuestas(respuesta(403, null));
        ShopifyPage shopify = new ShopifyPage(page, 1000, "Shop", BASE, 0, MAX);

        assertThat(shopify.scrapeAll()).isEmpty();
        assertThat(shopify.truncamiento()).startsWith("paginación cortada en p1: ").contains("HTTP 403");
        assertThat(shopify.truncamiento()).isEqualTo("paginación cortada en p1: bloqueado por la tienda (HTTP 403)");
    }

    @Test
    @DisplayName("a 500 is not a block: the message stays a bare HTTP status")
    void serverErrorIsNotMarkedAsABlock() {
        Page page = conRespuestas(respuesta(500, null));
        ShopifyPage shopify = new ShopifyPage(page, 1000, "Shop", BASE, 0, MAX);

        assertThat(shopify.scrapeAll()).isEmpty();
        assertThat(shopify.truncamiento()).isEqualTo("paginación cortada en p1: HTTP 500");
    }

    @Test
    @DisplayName("Shopify 429: waits Retry-After capped at 60s, retries twice at most, then reports HTTP 429")
    void shopify429Persistent() {
        Page page = conRespuestas(respuesta(429, "600"));
        ShopifyPage shopify = new ShopifyPage(page, 1000, "Shop", BASE, 0, MAX);

        assertThat(shopify.scrapeAll()).isEmpty();

        assertThat(esperas(page)).containsExactly(60_000L, 60_000L);
        assertThat(shopify.truncamiento()).contains("HTTP 429").contains("bloqueado por la tienda");
    }

    @Test
    @DisplayName("Shopify 429 without Retry-After waits 10s and a recovery keeps the products")
    void shopify429Recovers() {
        Page page = conRespuestas(respuesta(429, null), respuesta(200, null));
        when(page.evaluate("document.body.innerText")).thenReturn(UN_PRODUCTO);
        ShopifyPage shopify = new ShopifyPage(page, 1000, "Shop", BASE, 0, MAX);

        List<Product> productos = shopify.scrapeAll();

        assertThat(productos).hasSize(1);
        assertThat(shopify.truncamiento()).isNull();
        assertThat(esperas(page)).containsExactly(10_000L);
    }

    @Test
    @DisplayName("Shopify: a 2xx with an empty products array is a real empty store")
    void shopifyEmptyIsLegitimate() {
        Page page = conRespuestas(respuesta(200, null));
        when(page.evaluate("document.body.innerText")).thenReturn("{\"products\":[]}");
        ShopifyPage shopify = new ShopifyPage(page, 1000, "Shop", BASE, 0, MAX);

        assertThat(shopify.scrapeAll()).isEmpty();
        assertThat(shopify.truncamiento()).isNull();
    }

    @Test
    @DisplayName("Shopify: a 2xx that is not a products payload is an error")
    void shopifyNonCatalogBody() {
        Page page = conRespuestas(respuesta(200, null));
        when(page.evaluate("document.body.innerText")).thenReturn("{\"error\":\"nope\"}");
        ShopifyPage shopify = new ShopifyPage(page, 1000, "Shop", BASE, 0, MAX);

        assertThat(shopify.scrapeAll()).isEmpty();
        assertThat(shopify.truncamiento()).isNotBlank();
    }

    @Test
    @DisplayName("a mocked page whose navigate returns null is unknown, never a failure")
    void nullResponseIsUnknown() {
        Page page = mock(Page.class);
        when(page.evaluate("document.body.innerText")).thenReturn(UN_PRODUCTO);
        ShopifyPage shopify = new ShopifyPage(page, 1000, "Shop", BASE, 0, MAX);

        assertThat(shopify.scrapeAll()).hasSize(1);
        assertThat(shopify.truncamiento()).isNull();
    }

    @Test
    @DisplayName("the 429 waiting of one page object is bounded below the per-site timeout")
    void totalWaitIsBounded() {
        Page page = conRespuestas(respuesta(429, "60"));
        ShopifyPage shopify = new ShopifyPage(page, 1000, "Shop", BASE, 0, MAX);
        shopify.scrapeAll();

        assertThat(esperas(page).stream().mapToLong(Long::longValue).sum())
                .isLessThanOrEqualTo(BasePage.ESPERA_429_TOTAL_MAX_MS);
        assertThat(BasePage.ESPERA_429_TOTAL_MAX_MS).isLessThan(600_000L);
    }

    // ── osCommerce ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("osCommerce: a category page answering 403 is reported, not read as an empty page")
    void osCommerceCategory403() {
        Page page = conRespuestas(respuesta(403, null));
        OsCommercePage os = new OsCommercePage(page, 1000, "Venex", BASE, 0, MAX);

        assertThat(os.crawlLeafCategory(BASE + "/notebooks", "Notebooks")).isEmpty();
        assertThat(os.truncamiento()).contains("HTTP 403");
    }

    @Test
    @DisplayName("osCommerce: a 403 on the home page is reported too")
    void osCommerceHome403() {
        Page page = conRespuestas(respuesta(403, null));
        OsCommercePage os = new OsCommercePage(page, 1000, "Venex", BASE, 0, MAX);

        assertThat(os.scrapeAll()).isEmpty();
        assertThat(os.truncamiento()).contains("inicio").contains("HTTP 403");
    }

    private static final String HOME_CON_TRES_CATEGORIAS =
            "<a href=\"https://tienda.test/notebooks\">n</a><a href=\"https://tienda.test/monitores\">m</a>"
                    + "<a href=\"https://tienda.test/perifericos\">p</a>";

    @Test
    @DisplayName("osCommerce: a home that answers and categories that all refuse stop at the first 403")
    void osCommerceBlockedAtDiscoveryStopsAskingCategories() {
        Page page = conRespuestas(respuesta(200, null), respuesta(403, null));
        when(page.content()).thenReturn(HOME_CON_TRES_CATEGORIAS);
        OsCommercePage os = new OsCommercePage(page, 1000, "Venex", BASE, 0, MAX);

        assertThat(os.scrapeAll()).isEmpty();

        assertThat(os.truncamiento()).contains("categoría notebooks").contains("HTTP 403");
        verify(page, times(2)).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    @DisplayName("osCommerce: a 403 on one category after another answered does not abandon the rest")
    void osCommerceOneForbiddenCategoryKeepsGoing() {
        Page page = conRespuestas(respuesta(200, null), respuesta(200, null), respuesta(403, null));
        when(page.content()).thenReturn(HOME_CON_TRES_CATEGORIAS);
        OsCommercePage os = new OsCommercePage(page, 1000, "Venex", BASE, 0, MAX);

        os.scrapeAll();

        assertThat(os.truncamiento()).contains("HTTP 403");
        verify(page, atLeast(5)).navigate(anyString(), any(Page.NavigateOptions.class));
    }

    @Test
    @DisplayName("osCommerce: a category discovery answering 429 is retried after Retry-After")
    void osCommerceDiscovery429IsRetried() {
        Page page = conRespuestas(respuesta(200, null), respuesta(429, "3"), respuesta(200, null));
        when(page.content()).thenReturn(HOME_CON_TRES_CATEGORIAS);
        OsCommercePage os = new OsCommercePage(page, 1000, "Venex", BASE, 0, MAX);

        os.scrapeAll();

        assertThat(esperas(page)).startsWith(3_000L);
    }

    // ── Tiendanube ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Tiendanube: a catalog page answering 403 in the JS path is reported")
    void tiendanubeJs403() {
        Page page = conRespuestas(respuesta(403, null));
        TiendanubePage tn = new TiendanubePage(page, 1000, "Tn", BASE, 0, MAX);

        assertThat(tn.scrapeJs(BASE + "/productos/")).isEmpty();
        assertThat(tn.truncamiento()).contains("HTTP 403");
        verify(page, never()).evaluate(anyString());
    }

    @Test
    @DisplayName("a 404 past the first page is the end of a catalog, not a failure")
    void notFoundPastFirstPageEndsTheCatalog() {
        Page page = conRespuestas(respuesta(404, null));
        BasePage base = new BasePage(page, 1000) { };

        base.navegarExigiendoOk(BASE + "/x?page=2", 2);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> base.navegarExigiendoOk(BASE + "/x", 1))).isInstanceOf(BasePage.RespuestaHttpException.class);
    }
}
