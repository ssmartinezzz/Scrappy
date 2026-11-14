package ar.scraper.pages;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MagentoPage over listing pages recorded from laspepas.com.ar on 2026-11-11 (the product grid of each page,
 * wrapped in a minimal document). The crawl loop is driven with in-memory pages, no browser.
 */
@Epic("Scraping Engine")
@Feature("Magento")
@Story("Magento 2 listing HTML to Product mapping and the page crawl")
@DisplayName("MagentoPage — parseListado / crawlCatalogo")
class MagentoPageTest {

    private static final String SITIO = "Laspepas";
    private static final double MIN = 100;
    private static final double MAX = 10_000_000;

    private static String fixture(String name) {
        try (InputStream in = MagentoPageTest.class.getResourceAsStream("/fixtures/laspepas/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static List<Product> parse(String html) {
        return MagentoPage.parseListado(html, SITIO, MIN, MAX).productos();
    }

    @Test
    @DisplayName("a listing page yields its cards (one of the 12 is a dog item, left out) with name, final price, url and image")
    void parsesACatalogPage() {
        MagentoPage.Listado l = MagentoPage.parseListado(fixture("ver-todo-p1.html"), SITIO, MIN, MAX);

        assertThat(l.tarjetas()).isEqualTo(12);
        assertThat(l.productos()).hasSize(11);
        Product first = l.productos().get(0);
        assertThat(first.sitio()).isEqualTo(SITIO);
        assertThat(first.nombre()).isEqualTo("PANTALON PEYTON");
        assertThat(first.precio()).isEqualTo(119_900.0);
        assertThat(first.precioOriginal()).isNull();
        assertThat(first.url()).isEqualTo("https://laspepas.com.ar/ar/ss27/ver-todo/pantalon-peyton-negro-09");
        assertThat(first.imagenUrl()).startsWith("https://laspepas.com.ar/media/catalog/product/").endsWith(".jpg");
        assertThat(l.productos()).allSatisfy(p -> {
            assertThat(p.imagenUrl()).as(p.nombre()).startsWith("https://").doesNotContain("data:image");
            assertThat(p.genero()).isEqualTo("mujer");
            assertThat(p.talles()).isEmpty();
        });
    }

    @Test
    @DisplayName("a card whose first slide is lazy-loaded still gets its image, from data-src")
    void lazyFirstImage() {
        String card = """
                <li class="item product product-item-info"><div class="product-top">
                <a href="https://laspepas.com.ar/ar/x/a-09" class="product photo product-item-photo"><img src="data:image/gif;base64,R0lGOD" data-src="https://laspepas.com.ar/media/a.jpg" class="img-responsive product-image-photo owl-lazy" /></a></div>
                <h5 class="product name product-item-name"><a class="product-item-link" href="https://laspepas.com.ar/ar/x/a-09">CARTERA A</a></h5>
                <span data-price-amount="5000" data-price-type="finalPrice" class="price-wrapper"></span></li>""";
        assertThat(parse("<html lang=\"es\"><title>t</title><ol class=\"products\">" + card + "</ol></html>"))
                .singleElement().satisfies(p -> assertThat(p.imagenUrl()).isEqualTo("https://laspepas.com.ar/media/a.jpg"));
    }

    @Test
    @DisplayName("oldPrice becomes precioOriginal only when it is above the final price")
    void oldPriceBecomesPrecioOriginal() {
        List<Product> ps = parse(fixture("cuero-p3-con-oferta.html"));
        Product joan = ps.stream().filter(p -> p.url().endsWith("/campera-joan-beige-09")).findFirst().orElseThrow();
        assertThat(joan.precio()).isEqualTo(399_999.0);
        assertThat(joan.precioOriginal()).isEqualTo(599_900.0);
        assertThat(ps.stream().filter(p -> p != joan)).allSatisfy(p -> assertThat(p.precioOriginal()).isNull());

        String card = """
                <li class="item product product-item-info"><a class="product-item-link" href="https://laspepas.com.ar/ar/x/b-09">BOLSO B</a>
                <span data-price-amount="5000" data-price-type="finalPrice"></span><span data-price-amount="4000" data-price-type="oldPrice"></span></li>""";
        assertThat(parse("<ol class=\"products\">" + card + "</ol>")).singleElement()
                .satisfies(p -> assertThat(p.precioOriginal()).as("old price below the final price is noise").isNull());
    }

    @Test
    @DisplayName("names are unescaped and trimmed")
    void unescapesNames() {
        String card = """
                <li class="item product product-item-info"><a class="product-item-link" href="https://laspepas.com.ar/ar/x/c-09">CAMISA &amp; SACO &#039;C&#039; </a>
                <span data-price-amount="5000" data-price-type="finalPrice"></span></li>""";
        assertThat(parse("<ol class=\"products\">" + card + "</ol>")).singleElement()
                .satisfies(p -> assertThat(p.nombre()).isEqualTo("CAMISA & SACO 'C'"));
    }

    @Test
    @DisplayName("the kids line (pepas-mini) is infantil, by its catalog path, with or without 'PARA NIÑAS' in the name")
    void kidsLineIsInfantil() {
        List<Product> ps = parse(fixture("pepas-mini-p1.html"));
        assertThat(ps).hasSize(12).allSatisfy(p -> assertThat(p.genero()).as(p.nombre()).isEqualTo("infantil"));
        assertThat(ps).anySatisfy(p -> assertThat(p.nombre()).doesNotContain("NIÑAS"));
    }

    @Test
    @DisplayName("a 'mini' in an adult name (mini bag, mini skirt) is not kids: only the pepas-mini path or 'PARA NIÑAS/NIÑOS' is")
    void miniInAnAdultNameIsNotKids() {
        String card = """
                <li class="item product product-item-info"><a class="product-item-link" href="https://laspepas.com.ar/ar/shop/carteras/ver-todo-carteras/cartera-croco-luxor-mini-09">CARTERA CROCO LUXOR MINI</a>
                <span data-price-amount="5000" data-price-type="finalPrice"></span></li>
                <li class="item product product-item-info"><a class="product-item-link" href="https://laspepas.com.ar/ar/ss27/ver-todo/mini-falda-mystic-09">MINI FALDA DE JACQUARD MYSTIC</a>
                <span data-price-amount="5000" data-price-type="finalPrice"></span></li>
                <li class="item product product-item-info"><a class="product-item-link" href="https://laspepas.com.ar/ar/ss27/ver-todo/remera-x-09">REMERA PARA NIÑOS X</a>
                <span data-price-amount="5000" data-price-type="finalPrice"></span></li>""";
        assertThat(parse("<ol class=\"products\">" + card + "</ol>")).extracting(Product::genero)
                .containsExactly("mujer", "mujer", "infantil");
    }

    @Test
    @DisplayName("dog clothes that leak into the all-products listing are left out; the human items of that page stay")
    void petsAreExcluded() {
        MagentoPage.Listado l = MagentoPage.parseListado(fixture("ver-todo-p6-con-mascotas.html"), SITIO, MIN, MAX);
        assertThat(l.tarjetas()).isEqualTo(12);
        assertThat(l.productos()).hasSize(8)
                .noneMatch(p -> p.nombre().contains("PERRO") || p.nombre().contains("GUAU"));
    }

    @Test
    @DisplayName("everything under /shop/pets is excluded whatever its name says")
    void petsCatalogIsExcluded() {
        String card = """
                <li class="item product product-item-info"><a class="product-item-link" href="https://laspepas.com.ar/ar/shop/pets/collar-bow-09">COLLAR BOW</a>
                <span data-price-amount="5000" data-price-type="finalPrice"></span></li>""";
        assertThat(parse("<ol class=\"products\">" + card + "</ol>")).isEmpty();
    }

    @Test
    @DisplayName("a human item that only mentions 'perro' (a print) is kept")
    void perroPrintIsKept() {
        String card = """
                <li class="item product product-item-info"><a class="product-item-link" href="https://laspepas.com.ar/ar/ss27/ver-todo/blusa-issey-perro-sublimado-09">BLUSA ISSEY PERRO SUBLIMADO</a>
                <span data-price-amount="5000" data-price-type="finalPrice"></span></li>""";
        assertThat(parse("<ol class=\"products\">" + card + "</ol>")).hasSize(1);
    }

    @Test
    @DisplayName("the price band drops cards outside it but they still count as cards")
    void priceBand() {
        MagentoPage.Listado l = MagentoPage.parseListado(fixture("ver-todo-p1.html"), SITIO, 1_000_000, MAX);
        assertThat(l.tarjetas()).isEqualTo(12);
        assertThat(l.productos()).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"", "   ", "<html lang=\"es\"><title>x</title><body>nada</body></html>"})
    @DisplayName("blank or card-less html has zero cards")
    void noCards(String html) {
        assertThat(MagentoPage.parseListado(html, SITIO, MIN, MAX).tarjetas()).isZero();
    }

    @Test
    @DisplayName("the real page past the end of a catalogue has zero cards")
    void pastTheEnd() {
        assertThat(MagentoPage.parseListado(fixture("pasado-el-final.html"), SITIO, MIN, MAX).tarjetas()).isZero();
    }

    // ─── crawl ─────────────────────────────────────────────────────────────

    private static String card(String path, String slug, int precio) {
        return "<li class=\"item product product-item-info\"><a class=\"product-item-link\" href=\"https://laspepas.com.ar/ar/" + path + "/" + slug
                + "\">P " + slug + "</a><span data-price-amount=\"" + precio + "\" data-price-type=\"finalPrice\"></span></li>";
    }

    private static String pagina(String... cards) {
        return "<html lang=\"es\"><title>t</title><ol class=\"products\">" + String.join("", cards) + "</ol></html>";
    }

    private static String pagina(String path, int desde, int hasta) {
        List<String> cs = new ArrayList<>();
        for (int i = desde; i <= hasta; i++) cs.add(card(path, "p" + i, 1000 + i));
        return pagina(cs.toArray(new String[0]));
    }

    private List<Product> crawl(String catalogo, Map<String, String> paginas, int max, Set<String> vistas) {
        List<Product> out = new ArrayList<>();
        List<String> pedidas = new ArrayList<>();
        new MagentoPage(null, 0, SITIO, "https://laspepas.com.ar/ar/ss27/ver-todo", MIN, MAX, List.of(), max)
                .crawlCatalogo(catalogo, url -> {
                    pedidas.add(url);
                    return paginas.getOrDefault(url, pagina());
                }, vistas, out);
        pedidasUltimo = pedidas;
        return out;
    }

    private List<String> pedidasUltimo;

    @Test
    @DisplayName("pages are asked as ?p=N from 2, and the crawl stops at the first page with zero cards")
    void stopsAtTheFirstEmptyPage() {
        String cat = "https://laspepas.com.ar/ar/ss27/ver-todo";
        List<Product> out = crawl(cat, Map.of(cat, pagina("x", 1, 3), cat + "?p=2", pagina("x", 4, 5)), 60, new HashSet<>());

        assertThat(out).hasSize(5);
        assertThat(pedidasUltimo).containsExactly(cat, cat + "?p=2", cat + "?p=3");
    }

    @Test
    @DisplayName("a catalogue that serves its last page forever stops at the repeat")
    void stopsOnARepeatedPage() {
        String cat = "https://laspepas.com.ar/ar/ss27/ver-todo";
        String ultima = pagina("x", 1, 3);
        List<Product> out = crawl(cat, Map.of(cat, ultima, cat + "?p=2", ultima, cat + "?p=3", ultima), 60, new HashSet<>());

        assertThat(out).hasSize(3);
        assertThat(pedidasUltimo).containsExactly(cat, cat + "?p=2");
    }

    @Test
    @DisplayName("maxPaginas caps the crawl")
    void respectsMaxPaginas() {
        String cat = "https://laspepas.com.ar/ar/ss27/ver-todo";
        List<Product> out = crawl(cat, Map.of(cat, pagina("x", 1, 2), cat + "?p=2", pagina("x", 3, 4), cat + "?p=3", pagina("x", 5, 6)), 2, new HashSet<>());

        assertThat(out).hasSize(4);
        assertThat(pedidasUltimo).hasSize(2);
    }

    @Test
    @DisplayName("zero cards on page 1 is an explicit failure, never a silent empty catalogue")
    void emptyFirstPageThrows() {
        String cat = "https://laspepas.com.ar/ar/ss27/ver-todo";
        assertThatThrownBy(() -> crawl(cat, Map.of(), 60, new HashSet<>()))
                .isInstanceOf(MagentoPayloadException.class)
                .hasMessageContaining(cat);
    }

    @Test
    @DisplayName("a page of cards all outside the price band does not end the crawl")
    void filteredPageDoesNotEndTheCrawl() {
        String cat = "https://laspepas.com.ar/ar/ss27/ver-todo";
        String cara = pagina(card("x", "caro1", 99_999_999), card("x", "caro2", 99_999_999));
        List<Product> out = crawl(cat, Map.of(cat, cara, cat + "?p=2", pagina("x", 1, 2)), 60, new HashSet<>());

        assertThat(out).hasSize(2);
    }

    @Test
    @DisplayName("the same product listed under another catalogue path is one product: first url wins")
    void dedupsBySlugAcrossCatalogues() {
        Set<String> vistas = new HashSet<>();
        String a = "https://laspepas.com.ar/ar/ss27/ver-todo";
        String b = "https://laspepas.com.ar/ar/shop/carteras/ver-todo-carteras";
        List<Product> out = new ArrayList<>();
        MagentoPage page = new MagentoPage(null, 0, SITIO, a, MIN, MAX, List.of(), 60);
        page.crawlCatalogo(a, url -> url.equals(a) ? pagina("ss27/ver-todo", 1, 3) : pagina(), vistas, out);
        page.crawlCatalogo(b, url -> url.equals(b) ? pagina("shop/carteras/ver-todo-carteras", 2, 5) : pagina(), vistas, out);

        assertThat(out).extracting(Product::url).hasSize(5)
                .contains("https://laspepas.com.ar/ar/ss27/ver-todo/p2")
                .doesNotContain("https://laspepas.com.ar/ar/shop/carteras/ver-todo-carteras/p2");
    }

    @Test
    @DisplayName("a product repeated inside one catalogue (jeans lists two twice) is kept once")
    void dedupsWithinACatalogue() {
        String cat = "https://laspepas.com.ar/ar/shop/jeans/ver-todo-jeans";
        List<Product> out = crawl(cat, Map.of(cat, pagina("x", 1, 3), cat + "?p=2", pagina("x", 3, 5)), 60, new HashSet<>());

        assertThat(out).extracting(Product::nombre).doesNotHaveDuplicates().hasSize(5);
    }

    @Test
    @DisplayName("catalogue urls: the base first, then the extras, without repeats")
    void catalogoUrls() {
        MagentoPage page = new MagentoPage(null, 0, SITIO, "https://laspepas.com.ar/ar/ss27/ver-todo/", MIN, MAX,
                List.of("https://laspepas.com.ar/ar/shop/zapatos", "https://laspepas.com.ar/ar/ss27/ver-todo"), 60);
        assertThat(page.catalogos()).containsExactly("https://laspepas.com.ar/ar/ss27/ver-todo",
                "https://laspepas.com.ar/ar/shop/zapatos");
    }

    @Test
    @DisplayName("stock: the Magento out-of-stock marker flags the card false; a card without it stays unknown")
    void stockFromTheUnavailableMarker() {
        String html = fixture("ver-todo-p1.html");
        assertThat(parse(html)).allSatisfy(p -> assertThat(p.enStock()).isNull());

        String agotada = html.replaceFirst("(<li class=\"item product[^>]*>)",
                "$1<div class=\"stock unavailable\"><span>Out of stock</span></div>");
        List<Product> ps = parse(agotada);

        assertThat(ps).hasSize(11);
        assertThat(ps.get(0).enStock()).isFalse();
        assertThat(ps.stream().skip(1)).allSatisfy(p -> assertThat(p.enStock()).isNull());
    }
}
