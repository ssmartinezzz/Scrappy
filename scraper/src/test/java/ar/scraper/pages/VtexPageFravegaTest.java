package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Epic("Scraping")
@Feature("VTEX per-site settings")
@Story("Fravega: own seller, pruned service nodes, price floor")
@DisplayName("VtexPage — Fravega settings, every other site unchanged")
class VtexPageFravegaTest {

    private static final String BASE = "https://tienda.test";
    private static final String BUSQUEDA = BASE + "/api/catalog_system/pub/products/search";
    private static final String TREE = """
            [{"id":1,"name":"Servicios","children":[{"id":10,"name":"Plan Canje","children":[]}]},
             {"id":2,"name":"Sugerencias","children":[]},
             {"id":3,"name":"Cotillón NO USAR","children":[]},
             {"id":4,"name":"Bolsas de sucursal","children":[]},
             {"id":5,"name":"Prueba de categoría","children":[]},
             {"id":6,"name":"Mens","children":[]},
             {"id":7,"name":"Heladeras","children":[]}]""";

    private APIRequestContext api;
    private Page page;
    private final List<String> pedidas = new ArrayList<>();

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        api = mock(APIRequestContext.class);
        when(page.request()).thenReturn(api);
    }

    private static APIResponse resp(int status, String body, Map<String, String> headers) {
        APIResponse r = mock(APIResponse.class);
        when(r.ok()).thenReturn(status >= 200 && status < 300);
        when(r.status()).thenReturn(status);
        when(r.headers()).thenReturn(headers);
        when(r.text()).thenReturn(body);
        return r;
    }

    private static String productos(double... precios) {
        int[] i = {0};
        return "[" + java.util.Arrays.stream(precios).mapToObj(p -> """
                {"productName":"Heladera %d","linkText":"heladera-%d","items":[{"sellers":[{"commertialOffer":{"Price":%s,"ListPrice":0}}]}]}"""
                .formatted(i[0], i[0]++, p)).collect(Collectors.joining(",")) + "]";
    }

    private void apiResponde(String arbol, String body) {
        when(api.get(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            pedidas.add(url);
            if (url.contains("category/tree")) return resp(200, arbol, Map.of());
            if (url.contains("_from=0&_to=0")) return resp(206, "[]", Map.of("resources", "0-0/3"));
            return resp(206, body, Map.of("resources", "0-49/3"));
        });
    }

    private List<Product> scrape(String sitio, double min) {
        return new VtexPage(page, 1000, sitio, BASE, min, 5_000_000).scrapeAll();
    }

    private List<String> conteos() {
        return pedidas.stream().filter(u -> u.contains("_from=0&_to=0")).toList();
    }

    @Test
    @DisplayName("fravega: the count and the crawl carry the seller filter")
    void fravegaLlevaElVendedor() {
        apiResponde("[{\"id\":7,\"name\":\"Heladeras\",\"children\":[]}]", productos(2000, 3000, 4000));

        scrape("Fravega", 0);

        assertThat(pedidas).contains(
                BUSQUEDA + "?_from=0&_to=0&fq=C:/7/&fq=sellerId:1",
                BUSQUEDA + "?_from=0&_to=49&fq=C:/7/&fq=sellerId:1&O=OrderByReleaseDateDESC");
    }

    @Test
    @DisplayName("another site: the count and crawl URLs are exactly what they were")
    void otroSitioNoCambia() {
        apiResponde("[{\"id\":7,\"name\":\"Heladeras\",\"children\":[]}]", productos(2000, 3000, 4000));

        scrape("Vtex", 0);

        assertThat(pedidas).containsExactly(
                BASE + "/api/catalog_system/pub/category/tree/3",
                BUSQUEDA + "?_from=0&_to=0&fq=C:/7/",
                BUSQUEDA + "?_from=0&_to=49&fq=C:/7/&O=OrderByReleaseDateDESC");
    }

    @Test
    @DisplayName("fravega: Servicios and every junk node are never counted, so never crawled")
    void fravegaPodaAntesDeContar() {
        apiResponde(TREE, productos(2000, 3000, 4000));

        scrape("Fravega", 0);

        assertThat(conteos()).containsExactly(BUSQUEDA + "?_from=0&_to=0&fq=C:/7/&fq=sellerId:1");
        assertThat(pedidas).noneMatch(u -> u.contains("fq=C:/1/") || u.contains("fq=C:/2/")
                || u.contains("fq=C:/3/") || u.contains("fq=C:/4/") || u.contains("fq=C:/5/")
                || u.contains("fq=C:/6/"));
    }

    @Test
    @DisplayName("another site with a Servicios node still counts it, as today")
    void otroSitioSigueContandoLosServicios() {
        apiResponde(TREE, productos(2000, 3000, 4000));

        scrape("Vtex", 0);

        assertThat(conteos()).contains(BUSQUEDA + "?_from=0&_to=0&fq=C:/1/");
    }

    @Test
    @DisplayName("fravega floor: 999 is dropped, 1000 is kept, on top of the global range")
    void fravegaPisoDePrecio() {
        apiResponde("[{\"id\":7,\"name\":\"Heladeras\",\"children\":[]}]", productos(0, 1, 99, 999, 1000, 1999));

        assertThat(scrape("Fravega", 0)).extracting(Product::precio).containsExactly(1000.0, 1999.0);
        assertThat(scrape("Fravega", 1500)).extracting(Product::precio).containsExactly(1999.0);
    }

    @Test
    @DisplayName("another site keeps a 500 row when the global range allows it")
    void otroSitioSinPiso() {
        apiResponde("[{\"id\":7,\"name\":\"Heladeras\",\"children\":[]}]", productos(500, 999));

        assertThat(scrape("Vtex", 100)).extracting(Product::precio).containsExactly(500.0, 999.0);
    }
}
