package ar.scraper.pages;

import ar.scraper.model.Product;
import com.microsoft.playwright.Page;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Epic("Scraping Engine")
@Feature("TiendaNube Parsing")
@Story("Canonical product URL")
@DisplayName("Tiendanube — the entry params are not part of a product's identity")
class TiendanubeUrlCanonicalTest {

    private static final String BASE = "https://tienda.test";

    @Test
    @DisplayName("only entry_path and entry_category_id go; other params, their order and the fragment stay")
    void canonicalForm() {
        TiendanubeUrlCases.CASOS.forEach((entrada, esperada) ->
                assertThat(TiendanubeUrl.canonica(entrada)).as(entrada).isEqualTo(esperada));
    }

    @Test
    @DisplayName("canonicalising is idempotent")
    void idempotent() {
        TiendanubeUrlCases.CASOS.keySet().forEach(u ->
                assertThat(TiendanubeUrl.canonica(TiendanubeUrl.canonica(u))).isEqualTo(TiendanubeUrl.canonica(u)));
    }

    @Test
    @DisplayName("a blank url passes through untouched")
    void blankUrl() {
        assertThat(TiendanubeUrl.canonica(null)).isNull();
        assertThat(TiendanubeUrl.canonica("")).isEmpty();
    }

    private static String item(String slug, String suffix) {
        return "{\"nombre\":\"" + slug + "\",\"precio\":\"1000\",\"compare\":\"\",\"url\":\"/productos/" + slug
                + "/" + suffix + "\",\"img\":\"\",\"talles\":[],\"genero\":\"\"}";
    }

    /** A listing whose pages are the given JSON arrays, keyed by the page number in the last navigated url. */
    private static TiendanubePage paginaCon(List<String> paginas, List<String> visitadas) {
        Page page = mock(Page.class);
        AtomicReference<String> actual = new AtomicReference<>("");
        when(page.navigate(anyString(), any(Page.NavigateOptions.class))).thenAnswer(inv -> {
            actual.set(inv.getArgument(0));
            visitadas.add(inv.getArgument(0));
            return null;
        });
        when(page.evaluate(anyString())).thenAnswer(inv -> {
            if (!((String) inv.getArgument(0)).contains("JSON.stringify(results)")) return null;
            var m = java.util.regex.Pattern.compile("page=(\\d+)").matcher(actual.get());
            int n = m.find() ? Integer.parseInt(m.group(1)) : 1;
            return n <= paginas.size() ? paginas.get(n - 1) : "[]";
        });
        return new TiendanubePage(page, 1000, "Tn", BASE, 0, 5_000_000) {
            @Override
            protected void esperarListado() {
            }
        };
    }

    @Test
    @DisplayName("a JS listing yields the canonical url, whatever category the link came from")
    void jsListingYieldsCanonicalUrls() {
        TiendanubePage tn = paginaCon(List.of("[" + item("remera", "?entry_path=category&entry_category_id=5") + "]"),
                new ArrayList<>());

        assertThat(tn.scrapeJs(BASE + "/productos/")).extracting(Product::url)
                .containsExactly(BASE + "/productos/remera/");
    }

    @Test
    @DisplayName("pages that share some products keep paginating; only an identical page stops the crawl")
    void overlappingPagesKeepGoingIdenticalPageStops() {
        List<String> visitadas = new ArrayList<>();
        String compartido = item("remera", "?entry_path=category&entry_category_id=5");
        String compartidoOtraCategoria = item("remera", "?entry_path=category&entry_category_id=9");
        TiendanubePage tn = paginaCon(List.of(
                "[" + compartido + "," + item("pantalon", "?entry_path=category&entry_category_id=5") + "]",
                "[" + compartidoOtraCategoria + "," + item("buzo", "?entry_path=category&entry_category_id=9") + "]",
                "[" + item("campera", "") + "]"), visitadas);

        List<Product> productos = tn.scrapeJs(BASE + "/productos/");

        assertThat(productos).extracting(Product::nombre)
                .containsExactly("remera", "pantalon", "remera", "buzo", "campera");
        assertThat(visitadas).anyMatch(u -> u.contains("page=3"));
    }

    @Test
    @DisplayName("a page that repeats the previous one stops the crawl even with different entry params")
    void identicalPageStopsTheCrawl() {
        List<String> visitadas = new ArrayList<>();
        String p1 = "[" + item("remera", "?entry_path=category") + "," + item("buzo", "?entry_path=category") + "]";
        String p2 = "[" + item("remera", "?entry_path=category&entry_category_id=5") + ","
                + item("buzo", "?entry_path=category&entry_category_id=5") + "]";
        TiendanubePage tn = paginaCon(List.of(p1, p2, p2, p2), visitadas);

        assertThat(tn.scrapeJs(BASE + "/productos/")).hasSize(2);
        assertThat(visitadas).noneMatch(u -> u.contains("page=4"));
    }

    @Test
    @DisplayName("each collection of a site starts its own crawl: the same products on two of them are all reached")
    void sameProductsOnTwoCollectionsAreBothCrawled() {
        List<String> visitadas = new ArrayList<>();
        String pagina = "[" + item("remera", "?entry_path=category&entry_category_id=5") + "]";
        TiendanubePage tn = paginaCon(List.of(pagina, "[]", "[]"), visitadas);

        assertThat(tn.scrapeJs(BASE + "/productos/")).hasSize(1);
        visitadas.clear();
        assertThat(tn.scrapeJs(BASE + "/productos/otra/")).hasSize(1);
        assertThat(visitadas).isNotEmpty();
    }

    @Test
    @DisplayName("products that collapse to one url inside a batch are kept once, first listing wins")
    void collapsedUrlsAreKeptOnce() {
        Product a = Product.builder().sitio("Tn").nombre("Remera").precio(1000).url(BASE + "/productos/r/").build();
        Product b = a.toBuilder().precio(900).build();
        Product sinUrl = a.toBuilder().url("").nombre("Sin url").build();

        List<Product> unicos = TiendanubePage.unicosPorUrl(List.of(a, b, sinUrl, sinUrl));

        assertThat(unicos).containsExactly(a, sinUrl, sinUrl);
    }
}
