package ar.scraper.pages;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Scraping Engine")
@Feature("Odoo website_sale")
@DisplayName("OdooPage: /shop card parser and pagination (real captures, trimmed)")
class OdooPageParseTest {

    private static final String MAC_BASE = "https://www.macstation.com.ar";
    private static final String GEN_BASE = "https://www.gentech.com.ar";
    private static final String MAC = fixture("macstation-shop.html");
    private static final String GEN = fixture("gentech-shop.html");

    private static OdooPage.Listado parse(String html, String base) {
        return OdooPage.parseListado(html, "Macstation", base, 0, 50_000_000);
    }

    private static OdooPage pagina(String base) {
        return new OdooPage(null, 0, "Macstation", base, 0, 50_000_000) {
            @Override
            void pausa() {
                // no browser in a unit test
            }
        };
    }

    private static Product por(List<Product> ps, String fragmentoUrl) {
        return ps.stream().filter(p -> p.url().contains(fragmentoUrl)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Macstation: full name from itemprop content, numeric price, absolute url, bigger image")
    void parsesMacstationCards() {
        OdooPage.Listado l = parse(MAC, MAC_BASE);
        assertThat(l.tarjetas()).isEqualTo(3);
        assertThat(l.productos()).hasSize(3);
        Product mac = por(l.productos(), "mhfa4le");
        assertThat(mac.nombre()).isEqualTo("MacBook Neo 13\" (A18 Pro) - 6 CPU, 5 GPU, 8GB, 256GB, Magic Keyboard - Plata");
        assertThat(mac.precio()).isEqualTo(1_999_990.0);
        assertThat(mac.precioOriginal()).as("a d-none <del> is no discount").isNull();
        assertThat(mac.url()).isEqualTo(MAC_BASE
                + "/shop/mhfa4le-a-macbook-neo-13-a18-pro-6-cpu-5-gpu-8gb-256gb-magic-keyboard-plata-102425");
        assertThat(mac.imagenUrl()).isEqualTo(MAC_BASE + "/web/image/product.template/102425/image_512?unique=30eac4a");
        assertThat(mac.categoria()).isEmpty();
    }

    @Test
    @DisplayName("Macstation: a visible <del> is the list price")
    void readsListPrice() {
        Product iphone = por(parse(MAC, MAC_BASE).productos(), "iphone-16-plus-98594");
        assertThat(iphone.precio()).isEqualTo(2_999_990.0);
        assertThat(iphone.precioOriginal()).isEqualTo(4_299_990.0);
    }

    @Test
    @DisplayName("Gentech: name from the title span, price from oe_currency_value in es-AR format, primary image")
    void parsesGentechCards() {
        OdooPage.Listado l = parse(GEN, GEN_BASE);
        assertThat(l.productos()).hasSize(3);
        Product whey = por(l.productos(), "whey-protein-afa-1kg-130");
        assertThat(whey.nombre()).isEqualTo("Whey Protein AFA 1kg");
        assertThat(whey.precio()).isEqualTo(105_900.0);
        assertThat(whey.precioOriginal()).isNull();
        assertThat(whey.url()).isEqualTo(GEN_BASE + "/shop/whey-protein-afa-1kg-130");
        assertThat(whey.imagenUrl()).contains("/web/image/product.product/1968/image_1024/");
        assertThat(por(l.productos(), "908g").precio()).isEqualTo(104_899.99);
    }

    @Test
    @DisplayName("the en-US price format and a /en language prefix give the same price and url")
    void readsEnglishLocale() {
        String en = GEN.replace("105.900,00", "105,900.00").replace("104.899,99", "104,899.99")
                .replace("href=\"/shop/", "href=\"/en/shop/");
        List<Product> ps = parse(en, GEN_BASE).productos();
        assertThat(por(ps, "whey-protein-afa-1kg-130").precio()).isEqualTo(105_900.0);
        assertThat(por(ps, "908g").precio()).isEqualTo(104_899.99);
        assertThat(por(ps, "whey-protein-afa-1kg-130").url()).isEqualTo(GEN_BASE + "/shop/whey-protein-afa-1kg-130");
    }

    @Test
    @DisplayName("a themed discount: the <del> amount is the list price, the other one the sale price")
    void readsThemedDiscount() {
        String venta = "<span class=\"oe_currency_value\">105.900,00</span>";
        String conDescuento = GEN.replace(venta,
                "<del class=\"text-muted me-1\">$ <span class=\"oe_currency_value\">129.900,00</span></del>" + venta);
        assertThat(conDescuento).isNotEqualTo(GEN);
        Product whey = por(parse(conDescuento, GEN_BASE).productos(), "whey-protein-afa-1kg-130");
        assertThat(whey.precio()).isEqualTo(105_900.0);
        assertThat(whey.precioOriginal()).isEqualTo(129_900.0);
    }

    @Test
    @DisplayName("a card-payment discount listed as a product is not a product, but its card still counts")
    void skipsDiscountPseudoProducts() {
        String falso = MAC.replace("content=\"iPhone 16 Plus\"", "content=\"10% de descuento tarjeta de crédito\"");
        assertThat(falso).isNotEqualTo(MAC);
        OdooPage.Listado l = parse(falso, MAC_BASE);
        assertThat(l.productos()).extracting(Product::nombre).noneMatch(n -> n.contains("descuento"));
        assertThat(l.productos()).hasSize(2);
        assertThat(l.tarjetas()).isEqualTo(3);
    }

    @Test
    @DisplayName("monto reads both locales and keeps a three-digit group as thousands")
    void montoBothLocales() {
        assertThat(OdooPage.monto("105.900,00")).isEqualTo(105_900.0);
        assertThat(OdooPage.monto("43,900.00")).isEqualTo(43_900.0);
        assertThat(OdooPage.monto("1999990.0")).isEqualTo(1_999_990.0);
        assertThat(OdooPage.monto("$ 4.299.990")).isEqualTo(4_299_990.0);
        assertThat(OdooPage.monto("1.999")).isEqualTo(1_999.0);
        assertThat(OdooPage.monto("12500")).isEqualTo(12_500.0);
        assertThat(OdooPage.monto("sin precio")).isNull();
        assertThat(OdooPage.monto(null)).isNull();
    }

    @Test
    @DisplayName("the price band drops a card but still counts it for the end-of-pages check")
    void priceBandKeepsTheCardCount() {
        OdooPage.Listado l = OdooPage.parseListado(MAC, "Macstation", MAC_BASE, 2_500_000, 50_000_000);
        assertThat(l.productos()).extracting(Product::nombre).containsExactly("iPhone 16 Plus");
        assertThat(l.tarjetas()).isEqualTo(3);
        assertThat(l.urls()).hasSize(3);
    }

    @Test
    @DisplayName("the last page number comes from the pagination links, with or without ?ppg")
    void readsLastPage() {
        assertThat(OdooPage.ultimaPagina(MAC)).isEqualTo(47);
        assertThat(OdooPage.ultimaPagina(GEN)).isEqualTo(3);
        assertThat(OdooPage.ultimaPagina("<html></html>")).isZero();
        assertThat(OdooPage.ultimaPagina(null)).isZero();
    }

    @Test
    @DisplayName("blank or card-less html is an empty listing, not an error")
    void emptyListing() {
        assertThat(OdooPage.parseListado("", "x", MAC_BASE, 0, 1).tarjetas()).isZero();
        assertThat(OdooPage.parseListado("<html><body>nothing</body></html>", "x", MAC_BASE, 0, 1).tarjetas()).isZero();
    }

    private static String conPaginacion(String html, int ultima) {
        return html.replaceAll("/shop/page/\\d+", "/shop/page/" + ultima);
    }

    @Test
    @DisplayName("the crawl stops at the announced last page")
    void stopsAtTheAnnouncedLastPage() {
        String p1 = GEN;
        String p2 = GEN.replace("href=\"/shop/", "href=\"/shop/p2-");
        int[] pedidos = {0};
        IntFunction<String> htmlDe = n -> {
            pedidos[0]++;
            return n == 2 ? p2 : "<html><body>" + n + "</body></html>";
        };
        OdooPage page = pagina(GEN_BASE);
        // announced 2 pages: page 3 must never be requested
        List<Product> ps = page.crawl(conPaginacion(p1, 2), htmlDe);
        assertThat(pedidos[0]).isEqualTo(1);
        assertThat(ps).hasSize(6);
        assertThat(page.truncamiento()).isNull();
    }

    @Test
    @DisplayName("a page that only repeats an earlier one ends the crawl without a truncation")
    void stopsOnARepeatedPage() {
        OdooPage page = pagina(MAC_BASE);
        int[] pedidos = {0};
        List<Product> ps = page.crawl(MAC, n -> {
            pedidos[0]++;
            return MAC; // the shop clamps a page past the end to the last one
        });
        assertThat(ps).hasSize(3);
        assertThat(pedidos[0]).isEqualTo(1);
        assertThat(page.truncamiento()).isNull();
    }

    @Test
    @DisplayName("an empty page before the announced last one is a truncation, and what was read is kept")
    void emptyMiddlePageIsATruncation() {
        OdooPage page = pagina(GEN_BASE);
        List<Product> ps = page.crawl(GEN, n -> "<html><body></body></html>");
        assertThat(ps).hasSize(3);
        assertThat(page.truncamiento()).contains("p2");
    }

    @Test
    @DisplayName("a fetch that fails after the first page is a truncation; on the first page it fails loud")
    void fetchFailures() {
        OdooPage page = pagina(GEN_BASE);
        List<Product> ps = page.crawl(GEN, n -> {
            throw new IllegalStateException("net down");
        });
        assertThat(ps).hasSize(3);
        assertThat(page.truncamiento()).contains("net down");

        assertThatThrownBy(() -> pagina(GEN_BASE).crawl("<html></html>", n -> ""))
                .isInstanceOf(OdooPayloadException.class);
    }

    private static String fixture(String name) {
        try (InputStream in = OdooPageParseTest.class.getResourceAsStream("/fixtures/odoo/" + name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
