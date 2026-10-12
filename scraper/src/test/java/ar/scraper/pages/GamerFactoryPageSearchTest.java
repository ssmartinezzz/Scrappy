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
@DisplayName("GamerFactoryPage — search API parser (real capture)")
class GamerFactoryPageSearchTest {

    private static final String SEARCH = fixture("search.json");
    private static final String BASE = "https://www.gamerfactory.com.ar";

    private static GamerFactoryPage.Search parse(String json) {
        return GamerFactoryPage.parseSearch(json, "Gamer Factory", BASE, 0, 5_000_000);
    }

    private static Product porNombre(List<Product> ps, String fragmento) {
        return ps.stream().filter(p -> p.nombre().contains(fragmento)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("keeps every product, whatever its type")
    void keepsEveryType() {
        var s = parse(SEARCH);

        assertThat(s.productos()).hasSize(8);
        assertThat(s.crudos()).isEqualTo(8);
        assertThat(s.productos()).anyMatch(p -> p.nombre().startsWith("Simulador"))
                .anyMatch(p -> p.nombre().startsWith("PC Gamer"));
    }

    @Test
    @DisplayName("maps name, URL from the slug, first image and the tecnologia rubro; leaves marca to BrandExtractor")
    void mapsFields() {
        Product p = porNombre(parse(SEARCH).productos(), "Placa de video ASUS");

        assertThat(p.sitio()).isEqualTo("Gamer Factory");
        assertThat(p.url()).isEqualTo(BASE + "/producto/placa-de-video-asus-rtx-3050-6gb-gddr6-oc");
        assertThat(p.imagenUrl()).startsWith("https://s3.nubi2go.com/mediagf/").endsWith(".webp");
        // the raw API brand is not in the marca table and breaks fk_productos_marca on upsert
        assertThat(p.marca()).isEmpty();
        assertThat(p.rubro()).isEqualTo("tecnologia");
    }

    @Test
    @DisplayName("price is special.ARS; precioOriginal only when strikethrough > ARS")
    void precioOriginalOnlyOnRealDiscount() {
        List<Product> ps = parse(SEARCH).productos();

        Product sinDescuento = porNombre(ps, "Core i3 12100");
        assertThat(sinDescuento.precio()).isEqualTo(246000);
        assertThat(sinDescuento.precioOriginal()).isNull();

        Product conDescuento = porNombre(ps, "Targus W575");
        assertThat(conDescuento.precio()).isEqualTo(26800);
        assertThat(conDescuento.precioOriginal()).isEqualTo(29777);
        assertThat(ps.stream().filter(p -> p.precioOriginal() != null)).hasSize(3);
    }

    @Test
    @DisplayName("reads the announced total")
    void readsTotal() {
        assertThat(parse(SEARCH).total()).isEqualTo(828);
    }

    @Test
    @DisplayName("a product without images is kept, with an empty image")
    void missingImageKeepsTheProduct() {
        String json = """
                {"total":1,"data":[{"name":"Sin foto","brand":"X","slug":"sin-foto","images":[],
                 "price":{"special":{"ARS":1000,"strikethrough":1000}}}]}""";

        Product p = parse(json).productos().get(0);

        assertThat(p.imagenUrl()).isEmpty();
        assertThat(p.precio()).isEqualTo(1000);
    }

    @Test
    @DisplayName("the price band still applies, and raw count still counts the dropped rows")
    void priceBandApplies() {
        var s = GamerFactoryPage.parseSearch(SEARCH, "Gamer Factory", BASE, 0, 100_000);

        assertThat(s.productos()).allMatch(p -> p.precio() <= 100_000).hasSizeLessThan(8);
        assertThat(s.crudos()).isEqualTo(8);
    }

    @Test
    @DisplayName("HTML, empty or data-less payloads are an error, not an empty page")
    void unexpectedPayloadThrows() {
        for (String malo : new String[]{"", "<!doctype html><html></html>", "{\"foo\":1}",
                "{\"total\":3}", "{\"data\":[]}"}) {
            assertThatThrownBy(() -> parse(malo)).isInstanceOf(GamerFactoryPayloadException.class);
        }
    }

    @Test
    @DisplayName("total 0 with no data is an error: the catalogue is never empty")
    void emptyCatalogueThrows() {
        assertThatThrownBy(() -> parse("{\"total\":0,\"data\":[]}"))
                .isInstanceOf(GamerFactoryPayloadException.class);
    }

    @Test
    @DisplayName("the search URL carries page, perPage and URL-encoded empty filter and sort")
    void searchUrl() {
        assertThat(GamerFactoryPage.searchUrl(2))
                .isEqualTo("https://core.gamerfactory.com.ar/products/search?filter=%7B%7D&page=2&perPage=1000&sort=%7B%7D");
    }

    @Test
    @DisplayName("fewer products than announced after the last page is an error")
    void shortCatalogueThrows() {
        assertThatThrownBy(() -> GamerFactoryPage.verificarCompleto(8, 828))
                .isInstanceOf(GamerFactoryPayloadException.class);
        GamerFactoryPage.verificarCompleto(828, 828);
    }

    private static String fixture(String nombre) {
        try (InputStream in = GamerFactoryPageSearchTest.class
                .getResourceAsStream("/fixtures/gamerfactory/" + nombre)) {
            return new String(Objects.requireNonNull(in, nombre).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
