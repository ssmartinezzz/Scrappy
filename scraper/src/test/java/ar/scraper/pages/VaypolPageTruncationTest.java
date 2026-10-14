package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Run 66: net::ERR_NETWORK_CHANGED on p59 ended Vaypol with 3480 of 9960 products and no trace of
 * the cut, so the soft-delete deactivated the other 6536 rows.
 */
@Epic("Scraping")
@Feature("Vaypol")
@DisplayName("VaypolPage — a pagination cut is reported, not swallowed")
class VaypolPageTruncationTest {

    private static final String BASE = "https://www.vaypol.com.ar";

    /** Five full pages, then a short one: the catalog ends on its own. */
    private static String listado(int pagina) {
        String items = IntStream.range(0, pagina <= 5 ? 12 : 3)
                .mapToObj(i -> """
                        {"id":%d,"name":"Zapatilla %d-%d","url":"zapatilla-%d-%d-%d",
                         "all_prices":{"original":1000},
                         "dummy_images":[{"url":"https://cdn.test/%d-%d.webp"}]}"""
                        .formatted(pagina * 100 + i, pagina, i, pagina, i, pagina * 100 + i, pagina, i))
                .collect(java.util.stream.Collectors.joining(","));
        return "{\"props\":{\"pageProps\":{\"initialReduxState\":{\"products\":{\"items\":[" + items + "]}}}}}";
    }

    private static Page navegador(int paginaQueFalla, int vecesQueFalla) {
        Page page = Mockito.mock(Page.class);
        int[] actual = {0};
        int[] fallos = {0};
        Mockito.doAnswer(inv -> {
            String url = inv.getArgument(0);
            actual[0] = Integer.parseInt(url.substring(url.lastIndexOf('/') + 1));
            if (actual[0] == paginaQueFalla && fallos[0]++ < vecesQueFalla) {
                throw new PlaywrightException("Page.navigate: net::ERR_NETWORK_CHANGED at " + url);
            }
            return null;
        }).when(page).navigate(anyString(), any(Page.NavigateOptions.class));
        when(page.evaluate(contains("__NEXT_DATA__"))).thenAnswer(inv -> listado(actual[0]));
        return page;
    }

    @Test
    @DisplayName("a persistent network error keeps the pages already read and names the page that failed")
    void persistentErrorIsReported() {
        VaypolPage vaypol = new VaypolPage(navegador(3, Integer.MAX_VALUE), 1000, "Vaypol", BASE, 0, 5_000_000);

        List<Product> productos = vaypol.scrapeAll();

        assertThat(productos).hasSize(24);
        assertThat(vaypol.truncamiento())
                .startsWith("paginación cortada en p3: ")
                .contains("net::ERR_NETWORK_CHANGED");
    }

    @Test
    @DisplayName("a network error that heals on retry loses no page and reports nothing")
    void transientErrorIsInvisible() {
        VaypolPage vaypol = new VaypolPage(navegador(3, 1), 1000, "Vaypol", BASE, 0, 5_000_000);

        List<Product> productos = vaypol.scrapeAll();

        assertThat(productos).hasSize(5 * 12 + 3);
        assertThat(vaypol.truncamiento()).isNull();
    }
}
