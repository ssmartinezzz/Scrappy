package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A WAF that 403s Playwright's HTTP stack but lets the page's own fetch through (nike.com.ar). */
@Epic("Scraping")
@Feature("VTEX WAF fallback")
@DisplayName("VtexPage — 403 on page.request() falls back to an in-page fetch")
class VtexPageWafFallbackTest {

    private static final String BASE = "https://tienda.test";
    private static final double MAX = 5_000_000;
    private static final String TREE = "[{\"id\":7,\"name\":\"Calzado\",\"children\":[]}]";

    private Page page;
    private APIRequestContext api;
    private final List<String> fetched = new ArrayList<>();

    @BeforeEach
    void setUp() {
        page = mock(Page.class);
        api = mock(APIRequestContext.class);
        when(page.request()).thenReturn(api);
    }

    private static String productos(int n) {
        return "[" + IntStream.range(0, n).mapToObj(i -> """
                {"productName":"Zapatilla %d","linkText":"zapatilla-%d","items":[{"sellers":[{"commertialOffer":{"Price":1000,"ListPrice":0}}]}]}"""
                .formatted(i, i)).collect(Collectors.joining(",")) + "]";
    }

    private static APIResponse requestResp(int status, String body, Map<String, String> headers) {
        APIResponse resp = mock(APIResponse.class);
        when(resp.ok()).thenReturn(status >= 200 && status < 300);
        when(resp.status()).thenReturn(status);
        when(resp.headers()).thenReturn(headers);
        when(resp.text()).thenReturn(body);
        return resp;
    }

    private static Map<String, Object> fetchResp(int status, String body, String resources) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", status);
        m.put("body", body);
        m.put("resources", resources);
        return m;
    }

    /** Every in-page fetch is recorded by URL and answered by {@code respuesta}. */
    private void fetchResponde(Function<String, Map<String, Object>> respuesta) {
        when(page.evaluate(anyString(), any())).thenAnswer(inv -> {
            String url = inv.getArgument(1);
            fetched.add(url);
            return respuesta.apply(url);
        });
    }

    private static Map<String, Object> catalogoPorFetch(String url) {
        if (url.contains("category/tree")) return fetchResp(200, TREE, null);
        if (url.contains("_from=0&_to=0")) return fetchResp(206, "[]", "0-0/3");
        return fetchResp(206, productos(3), "0-49/3");
    }

    private VtexPage vtex() {
        return new VtexPage(page, 1000, "Vtex", BASE, 0, MAX);
    }

    @Test
    @DisplayName("403 through page.request(): the catalog is read through the page and parsed")
    void fallbackAFetch() {
        APIResponse bloqueo = requestResp(403, null, Map.of());
        when(api.get(anyString())).thenReturn(bloqueo);
        fetchResponde(VtexPageWafFallbackTest::catalogoPorFetch);

        List<Product> resultado = vtex().scrapeAll();

        assertThat(resultado).hasSize(3);
        assertThat(resultado.get(0).nombre()).isEqualTo("Zapatilla 0");
    }

    @Test
    @DisplayName("the resources header is read in fetch mode and drives the category split")
    void headerResourcesPorFetch() {
        APIResponse bloqueo = requestResp(403, null, Map.of());
        when(api.get(anyString())).thenReturn(bloqueo);
        fetchResponde(VtexPageWafFallbackTest::catalogoPorFetch);

        vtex().scrapeAll();

        assertThat(fetched).anyMatch(u -> u.contains("category/tree/3"));
        assertThat(fetched).anyMatch(u -> u.contains("_from=0&_to=0&fq=C:/7/"));
        assertThat(fetched).anyMatch(u -> u.contains("_from=0&_to=49&fq=C:/7/"));
    }

    @Test
    @DisplayName("fetch mode is sticky: after the first 403 page.request() is not tried again")
    void modoFetchEsPegajoso() {
        APIResponse bloqueo = requestResp(403, null, Map.of());
        when(api.get(anyString())).thenReturn(bloqueo);
        fetchResponde(VtexPageWafFallbackTest::catalogoPorFetch);

        vtex().scrapeAll();

        verify(api, times(1)).get(anyString());
    }

    @Test
    @DisplayName("fetch URLs are relative to the page origin")
    void urlsRelativasAlOrigen() {
        APIResponse bloqueo = requestResp(403, null, Map.of());
        when(api.get(anyString())).thenReturn(bloqueo);
        fetchResponde(VtexPageWafFallbackTest::catalogoPorFetch);

        vtex().scrapeAll();

        assertThat(fetched).isNotEmpty().allMatch(u -> u.startsWith("/api/"));
    }

    @Test
    @DisplayName("403 in both modes is an error, never an empty catalog")
    void bloqueoEnAmbosModosFalla() {
        APIResponse bloqueo = requestResp(403, null, Map.of());
        when(api.get(anyString())).thenReturn(bloqueo);
        fetchResponde(url -> fetchResp(403, "", null));

        assertThatThrownBy(() -> vtex().scrapeAll())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName("a 429 in fetch mode is retried like on the request path")
    void reintentaElTooManyRequestsPorFetch() {
        APIResponse bloqueo = requestResp(403, null, Map.of());
        when(api.get(anyString())).thenReturn(bloqueo);
        Map<String, Integer> intentos = new HashMap<>();
        fetchResponde(url -> {
            if (url.contains("category/tree") && intentos.merge("tree", 1, Integer::sum) == 1) {
                return fetchResp(429, "", null);
            }
            return catalogoPorFetch(url);
        });

        assertThat(vtex().scrapeAll()).hasSize(3);
        assertThat(intentos.get("tree")).isEqualTo(2);
    }

    @Test
    @DisplayName("without a 403 the in-page fetch is never used")
    void sinBloqueoNoUsaFetch() {
        when(api.get(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            if (url.contains("category/tree")) return requestResp(200, TREE, Map.of());
            if (url.contains("_from=0&_to=0")) return requestResp(206, "[]", Map.of("resources", "0-0/3"));
            return requestResp(206, productos(3), Map.of("resources", "0-49/3"));
        });

        assertThat(vtex().scrapeAll()).hasSize(3);
        verify(page, never()).evaluate(anyString(), any());
    }
}
