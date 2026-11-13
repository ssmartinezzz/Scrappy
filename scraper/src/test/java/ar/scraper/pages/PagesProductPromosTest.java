package ar.scraper.pages;

import ar.scraper.model.Product;
import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromoFinanciacion;
import ar.scraper.model.promo.TipoPromo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Real payloads, read through each page class: the promos land on {@code Product.promos}. */
@Epic("Scraping")
@Feature("Payment promos")
@DisplayName("Pages — product-scoped payment promos from the payload already in hand")
class PagesProductPromosTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String fixture(String path) throws Exception {
        try (InputStream in = PagesProductPromosTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String resumen(PromoFinanciacion p) {
        return p.medioPago() + "|" + p.tipo() + "|" + p.cuotas() + "|" + p.pct() + "|" + p.alcance();
    }

    private static List<String> resumenes(Product p) {
        return p.promos().stream().map(PagesProductPromosTest::resumen).toList();
    }

    @Test
    @DisplayName("VTEX Sporting: the max interest-free cuotas per resolvable medio; unknown media and interest abstain")
    void vtexInstallments() throws Exception {
        JsonNode rows = MAPPER.readTree(fixture("/fixtures/vtex-promos/products.json"));
        VtexPage page = new VtexPage(null, 0, "sporting", "https://www.sporting.com.ar", 100, 100_000_000);
        Method m = VtexPage.class.getDeclaredMethod("fromVtex", JsonNode.class, String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Product p = ((Optional<Product>) m.invoke(page, rows.get(0), "https://www.sporting.com.ar")).orElseThrow();

        assertThat(resumenes(p)).containsExactlyInAnyOrder(
                "Visa|CUOTAS_SIN_INTERES|12|null|PRODUCTO",
                "Cabal|CUOTAS_SIN_INTERES|3|null|PRODUCTO",
                "Naranja|CUOTAS_SIN_INTERES|3|null|PRODUCTO");
    }

    @Test
    @DisplayName("VTEX Easy: a single cuota and an internal campaign flag produce no promo")
    void vtexNothingToSay() throws Exception {
        JsonNode rows = MAPPER.readTree(fixture("/fixtures/vtex-promos/products.json"));
        VtexPage page = new VtexPage(null, 0, "easy", "https://www.easy.com.ar", 100, 100_000_000);
        Method m = VtexPage.class.getDeclaredMethod("fromVtex", JsonNode.class, String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Product p = ((Optional<Product>) m.invoke(page, rows.get(1), "https://www.easy.com.ar")).orElseThrow();

        assertThat(p.promos()).isEmpty();
    }

    @Test
    @DisplayName("Vaypol: quantity x value matching the price is interest-free, on the generic credit card")
    void vaypolInstallments() throws Exception {
        VaypolPage page = new VaypolPage(Mockito.mock(Page.class), 1000, "Vaypol",
                "https://www.vaypol.com.ar", 0, 5_000_000);
        List<Product> productos = page.productosDeNextData(fixture("/fixtures/vaypol/next-data-listado.json"),
                "https://www.vaypol.com.ar");

        assertThat(productos).hasSize(2);
        assertThat(resumenes(productos.get(0))).containsExactly("Tarjeta de crédito|CUOTAS_SIN_INTERES|6|null|PRODUCTO");
        assertThat(resumenes(productos.get(1))).containsExactly("Tarjeta de crédito|CUOTAS_SIN_INTERES|6|null|PRODUCTO");
    }

    @Test
    @DisplayName("Kevingston: '6 cuotas de $X*' is interest-free only because 6 x X equals the price")
    void kevingstonInstallments() throws Exception {
        var l = KevingstonPage.parseListado(fixture("/fixtures/kevingston/products-page1.json"),
                "Kevingston", 0, 50_000_000);

        assertThat(l.productos()).isNotEmpty().allSatisfy(p ->
                assertThat(resumenes(p)).containsExactly("Tarjeta de crédito|CUOTAS_SIN_INTERES|6|null|PRODUCTO"));
    }

    @Test
    @DisplayName("Gamer Factory: special below list is the transfer discount, as a percentage of list")
    void gamerFactoryTransferDiscount() throws Exception {
        var s = GamerFactoryPage.parseSearch(fixture("/fixtures/gamerfactory/search.json"),
                "Gamer Factory", "https://www.gamerfactory.com.ar", 0, 5_000_000);

        Product gpu = s.productos().stream().filter(p -> p.nombre().startsWith("Placa de video")).findFirst().orElseThrow();
        assertThat(gpu.precio()).isEqualTo(500400.0);
        assertThat(resumenes(gpu)).containsExactly("Transferencia|DESCUENTO|null|12.44|PRODUCTO");
        assertThat(gpu.promos().get(0).tipo()).isEqualTo(TipoPromo.DESCUENTO);
        assertThat(gpu.promos().get(0).alcance()).isEqualTo(Alcance.PRODUCTO);
    }

    @Test
    @DisplayName("Inpro: the description's '20% de descuento con transferencia.' goes through the text parser")
    void inproTransferDiscountFromDescription() throws Exception {
        List<Product> productos = InproPage.parsePayload(fixture("/inpro/categoria_payload.html"), "Inpro",
                "https://www.inpro.com.ar", 0, 3_000_000);

        Product silla = productos.stream().filter(p -> p.url().endsWith("/silla-ergonomica-inpro")).findFirst().orElseThrow();
        assertThat(resumenes(silla)).containsExactly("Transferencia|DESCUENTO|null|20.00|PRODUCTO");
        Product brazo = productos.stream().filter(p -> p.url().endsWith("/brazo-de-monitor")).findFirst().orElseThrow();
        assertThat(brazo.promos()).isEmpty();
    }

    @Test
    @DisplayName("Vaypol: filling in a missing image keeps everything else on the product, promos included")
    void vaypolImageEnrichmentKeepsPromos() throws Exception {
        VaypolPage page = new VaypolPage(Mockito.mock(Page.class), 1000, "Vaypol",
                "https://www.vaypol.com.ar", 0, 5_000_000);
        Product p = page.productosDeNextData(fixture("/fixtures/vaypol/next-data-listado.json"),
                "https://www.vaypol.com.ar").get(0).toBuilder().imagenUrl("").build();

        Product conFoto = VaypolPage.conImagen(p, "https://cdn/x.webp");

        assertThat(conFoto.imagenUrl()).isEqualTo("https://cdn/x.webp");
        assertThat(conFoto.promos()).isEqualTo(p.promos()).isNotEmpty();
        assertThat(conFoto).usingRecursiveComparison().ignoringFields("imagenUrl").isEqualTo(p);
    }
}
