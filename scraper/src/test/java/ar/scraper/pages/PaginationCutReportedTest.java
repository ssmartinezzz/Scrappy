package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Every pagination loop that gives up on an exception says so; a deliberate stop does not. */
@Epic("Scraping")
@Feature("Partial catalogs")
@DisplayName("Pagination loops — a cut mid-catalog is reported with its page number")
class PaginationCutReportedTest {

    private static final String BASE = "https://tienda.test";
    private static final double MAX = 5_000_000;

    private static Page navegador(Predicate<String> falla) {
        Page page = mock(Page.class);
        Mockito.doAnswer(inv -> {
            String url = inv.getArgument(0);
            if (falla.test(url)) {
                throw new PlaywrightException("Page.navigate: net::ERR_CONNECTION_RESET at " + url);
            }
            return null;
        }).when(page).navigate(anyString(), any(Page.NavigateOptions.class));
        return page;
    }

    private static String items(int n, java.util.function.IntFunction<String> item) {
        return IntStream.range(0, n).mapToObj(item).collect(Collectors.joining(","));
    }

    // ── Shopify ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Shopify: p2 failing keeps the 250 products of p1 and reports p2")
    void shopify() {
        Page page = navegador(url -> url.contains("page=2"));
        String body = "{\"products\":[" + items(250, i -> """
                {"title":"Remera %d","handle":"remera-%d","variants":[{"price":"1000"}]}""".formatted(i, i)) + "]}";
        when(page.evaluate("document.body.innerText")).thenReturn(body);
        ShopifyPage shopify = new ShopifyPage(page, 1000, "Shop", BASE, 0, MAX);

        List<Product> productos = shopify.scrapeAll();

        assertThat(productos).hasSize(250);
        assertThat(shopify.truncamiento()).startsWith("paginación cortada en p2: ").contains("net::ERR_");
    }

    @Test
    @DisplayName("Shopify: a short page is the end of the catalog, not a cut")
    void shopifyShortPageIsNotACut() {
        Page page = navegador(url -> false);
        when(page.evaluate("document.body.innerText")).thenReturn(
                "{\"products\":[{\"title\":\"Remera\",\"handle\":\"r\",\"variants\":[{\"price\":\"1000\"}]}]}");
        ShopifyPage shopify = new ShopifyPage(page, 1000, "Shop", BASE, 0, MAX);

        assertThat(shopify.scrapeAll()).hasSize(1);
        assertThat(shopify.truncamiento()).isNull();
    }

    // ── WooCommerce ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("WooCommerce: p2 failing keeps p1 and reports p2")
    void woocommerce() {
        Page page = navegador(url -> url.contains("/page/2/"));
        when(page.evaluate(contains("ul.products"))).thenReturn(Boolean.TRUE);
        when(page.evaluate(contains("li.product, .product-item"))).thenReturn("["
                + items(8, i -> "{\"nombre\":\"Remera %d\",\"precio\":\"1000\",\"url\":\"%s/p/%d\"}".formatted(i, BASE, i)) + "]");
        WooCommercePage woo = new WooCommercePage(page, 1000, "Woo", BASE, 0, MAX);

        List<Product> productos = woo.scrapeAll();

        assertThat(productos).hasSize(8);
        assertThat(woo.truncamiento()).startsWith("paginación cortada en p2: ");
    }

    // ── StorePage.crawlPaginas (Qloud, OsCommerce) ──────────────────────────

    static class Crawler extends StorePage {
        Crawler(Page page) {
            super(page, 1000, "Crawl", BASE, 0, MAX);
        }

        boolean crawl(List<Product> out) {
            return crawlPaginas(10, "slug=x", p -> BASE + "/x/?page=" + p,
                    html -> List.of(producto("Remera " + html, 1000, null, BASE + "/p/" + html, "", "", "", List.of())),
                    new java.util.HashSet<>(), out);
        }
    }

    @Test
    @DisplayName("crawlPaginas: p3 failing keeps p1-p2 and reports p3")
    void crawlPaginas() {
        Page page = navegador(url -> url.endsWith("page=3"));
        int[] n = {0};
        when(page.content()).thenAnswer(inv -> "c" + (++n[0]));
        Crawler crawler = new Crawler(page);
        List<Product> out = new java.util.ArrayList<>();

        crawler.crawl(out);

        assertThat(out).hasSize(2);
        assertThat(crawler.truncamiento()).startsWith("paginación cortada en p3: ");
    }

    @Test
    @DisplayName("crawlPaginas: a page with nothing new is the end, not a cut")
    void crawlPaginasEmptyPageIsNotACut() {
        Page page = navegador(url -> false);
        when(page.content()).thenReturn("igual");
        Crawler crawler = new Crawler(page);
        List<Product> out = new java.util.ArrayList<>();

        crawler.crawl(out);

        assertThat(out).hasSize(1);
        assertThat(crawler.truncamiento()).isNull();
    }

    // ── Tiendanube ──────────────────────────────────────────────────────────

    static class TiendanubeJs extends TiendanubePage {
        TiendanubeJs(Page page) {
            super(page, 1000, "Harvey", BASE + "/productos/", 0, MAX);
        }

        @Override
        protected boolean usaApi() {
            return false;
        }
    }

    @Test
    @DisplayName("Tiendanube JS: p3 failing keeps p1-p2 and reports p3")
    void tiendanubeJs() {
        Page page = navegador(url -> url.contains("page=3"));
        int[] n = {0};
        Mockito.doAnswer(inv -> {
            String js = inv.getArgument(0);
            if (!js.contains("nombre") || js.contains("scrollBy")) return null;
            int p = ++n[0];
            return "[" + items(5, i -> "{\"nombre\":\"Remera\",\"precio\":\"1000\",\"url\":\"/p/%d-%d\"}".formatted(p, i)) + "]";
        }).when(page).evaluate(anyString());
        TiendanubeJs tn = new TiendanubeJs(page);

        List<Product> productos = tn.scrapeAll();

        assertThat(productos).hasSize(10);
        assertThat(tn.truncamiento()).startsWith("paginación cortada en p3: ");
    }

    @Test
    @DisplayName("Tiendanube API: p2 failing keeps the 200 products of p1 and reports p2")
    void tiendanubeApi() {
        Page page = navegador(url -> url.contains("/products?per_page=200&page=2"));
        when(page.evaluate(contains("LS.store"))).thenReturn("123");
        when(page.evaluate("document.body.innerText")).thenReturn("[" + items(200, i -> """
                {"name":"Remera %d","canonical_url":"/p/%d","variants":[{"price":"1000"}]}""".formatted(i, i)) + "]");
        TiendanubePage tn = new TiendanubePage(page, 1000, "Harvey", BASE + "/productos/", 0, MAX);

        List<Product> productos = tn.scrapeAll();

        assertThat(productos).hasSize(200);
        assertThat(tn.truncamiento()).startsWith("paginación cortada en p2: ");
    }

    // ── VTEX ────────────────────────────────────────────────────────────────

    private static String vtexItems(int n, int desde) {
        return "[" + items(n, i -> """
                {"productName":"Remera %d","linkText":"remera-%d","items":[{"sellers":[{"commertialOffer":{"Price":1000,"ListPrice":0}}]}]}"""
                .formatted(desde + i, desde + i)) + "]";
    }

    private static APIResponse respuesta(boolean ok, int status, String body) {
        APIResponse resp = mock(APIResponse.class);
        when(resp.ok()).thenReturn(ok);
        when(resp.status()).thenReturn(status);
        when(resp.headers()).thenReturn(Map.of());
        when(resp.text()).thenReturn(body);
        return resp;
    }

    @Test
    @DisplayName("VTEX Legacy: a page failing after its retries keeps the earlier ones and reports it")
    void vtexLegacy() {
        Page page = navegador(url -> false);
        APIRequestContext api = mock(APIRequestContext.class);
        when(page.request()).thenReturn(api);
        APIResponse caida = respuesta(false, 503, null);
        APIResponse primera = respuesta(true, 200, vtexItems(50, 0));
        when(api.get(anyString())).thenReturn(caida);
        when(api.get(contains("_from=0"))).thenReturn(primera);
        VtexPage vtex = new VtexPage(page, 1000, "Vtex", BASE, 0, MAX);

        List<Product> productos = vtex.scrapeAll();

        assertThat(productos).hasSize(50);
        assertThat(vtex.truncamiento()).startsWith("paginación cortada en p2: ");
    }

    @Test
    @DisplayName("VTEX IO: page=2 failing keeps page 1 and reports it")
    void vtexIo() {
        Page page = navegador(url -> url.contains("page=2"));
        APIRequestContext api = mock(APIRequestContext.class);
        when(page.request()).thenReturn(api);
        APIResponse caida = respuesta(false, 404, null);
        when(api.get(anyString())).thenReturn(caida);
        String body = "{\"products\":" + vtexItems(50, 0) + ",\"pagination\":{\"last\":{\"index\":3}}}";
        when(page.evaluate("document.body.innerText")).thenReturn(body);
        VtexPage vtex = new VtexPage(page, 1000, "Vtex", BASE, 0, MAX);

        List<Product> productos = vtex.scrapeAll();

        assertThat(productos).hasSize(50);
        assertThat(vtex.truncamiento()).startsWith("paginación cortada en p2: ");
    }
}
