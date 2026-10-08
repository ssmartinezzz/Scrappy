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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Scraping Engine")
@Feature("Tech stores")
@DisplayName("PrestashopPage — listing JSON parser (real Armytech capture)")
class PrestashopPageListingTest {

    private static final String LISTING = fixture("listing.json");

    private static PrestashopPage.Listing parse(String json) {
        return PrestashopPage.parseListing(json, "Armytech", 0, 5_000_000);
    }

    private static Product porNombre(List<Product> ps, String fragmento) {
        return ps.stream().filter(p -> p.nombre().contains(fragmento)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("drops the price-0 brand placeholders and keeps every real product")
    void dropsPriceZeroRows() {
        List<Product> ps = parse(LISTING).productos();

        assertThat(ps).hasSize(22);
        assertThat(ps).noneMatch(p -> p.nombre().startsWith("Marca - "));
        assertThat(ps).allMatch(p -> p.precio() > 0);
    }

    @Test
    @DisplayName("maps name, url, image, category and the tecnologia rubro")
    void mapsFields() {
        Product p = porNombre(parse(LISTING).productos(), "Mousepad Fantech");

        assertThat(p.sitio()).isEqualTo("Armytech");
        assertThat(p.url()).startsWith("https://www.armytech.com.ar/");
        assertThat(p.imagenUrl()).endsWith("mousepad-fantech-basic-mp64-black-xl-64x21-cm.jpg");
        assertThat(p.categoria()).isEqualTo("Mousepad");
        assertThat(p.rubro()).isEqualTo("tecnologia");
    }

    @Test
    @DisplayName("precioOriginal only when has_discount and regular > price")
    void precioOriginalOnlyOnRealDiscount() {
        List<Product> ps = parse(LISTING).productos();

        Product conDescuento = porNombre(ps, "Mousepad Fantech");
        assertThat(conDescuento.precio()).isEqualTo(5038.89);
        assertThat(conDescuento.precioOriginal()).isEqualTo(7552.578);
        assertThat(ps.stream().filter(p -> p.precioOriginal() != null)).hasSize(1);
    }

    @Test
    @DisplayName("a product with cover:false is kept, with an empty image")
    void missingCoverKeepsTheProduct() {
        Product p = porNombre(parse(LISTING).productos(), "Ryzen 5 5600ge");

        assertThat(p.imagenUrl()).isEmpty();
        assertThat(p.precio()).isEqualTo(282357.94);
    }

    @Test
    @DisplayName("reads pagination.pages_count")
    void readsPagesCount() {
        assertThat(parse(LISTING).paginas()).isEqualTo(1);
    }

    @Test
    @DisplayName("the price band still applies")
    void priceBandApplies() {
        assertThat(PrestashopPage.parseListing(LISTING, "Armytech", 0, 10_000).productos())
                .allMatch(p -> p.precio() <= 10_000)
                .hasSizeLessThan(22);
    }

    @Test
    @DisplayName("HTML, empty or products-less payloads are an error, not an empty page")
    void unexpectedPayloadThrows() {
        for (String malo : new String[]{"", "<!doctype html><html></html>", "{\"foo\":1}"}) {
            assertThatThrownBy(() -> parse(malo)).isInstanceOf(PrestashopPayloadException.class);
        }
    }

    @Test
    @DisplayName("a valid payload with zero products parses to an empty page")
    void emptyProductsIsNotAnError() {
        var l = parse("{\"products\":[],\"pagination\":{\"pages_count\":0}}");

        assertThat(l.productos()).isEmpty();
        assertThat(l.paginas()).isZero();
    }

    private static String fixture(String nombre) {
        try (InputStream in = PrestashopPageListingTest.class
                .getResourceAsStream("/fixtures/armytech/" + nombre)) {
            return new String(Objects.requireNonNull(in, nombre).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
