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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Scraping Engine")
@Feature("Salesforce Commerce Cloud")
@DisplayName("SfccPage: Search-UpdateGrid tile parser (real capture, trimmed)")
class SfccPageParseTest {

    private static final String GRID = fixture("dexter-grid.html");
    private static final String BASE = "https://www.dexter.com.ar";

    private static SfccPage.Listado parse(String html) {
        return SfccPage.parseListado(html, "Dexter", BASE, 0, 5_000_000);
    }

    private static SfccPage.Tile tile(SfccPage.Listado l, String pid) {
        return l.tiles().stream().filter(t -> t.pid().equals(pid)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("reads every tile of the capture")
    void readsEveryTile() {
        SfccPage.Listado l = parse(GRID);

        assertThat(l.tarjetas()).isEqualTo(7);
        assertThat(l.tiles()).hasSize(7);
    }

    @Test
    @DisplayName("maps name, absolute product url, image and leaves marca to BrandExtractor")
    void mapsFields() {
        Product p = tile(parse(GRID), "NICN9675-002").producto();

        assertThat(p.sitio()).isEqualTo("Dexter");
        assertThat(p.nombre()).isEqualTo("Ojotas Nike Victori One Slide");
        assertThat(p.url()).startsWith(BASE + "/").endsWith("/NICN9675-002.html");
        assertThat(p.imagenUrl()).startsWith("https://www.dexter.com.ar/dw/image/").contains("&sh=600").doesNotContain("&amp;");
        // the tracking brand is not a seeded marca and breaks fk_productos_marca
        assertThat(p.marca()).isEmpty();
    }

    @Test
    @DisplayName("price is the sales value; precioOriginal only from a list price above it")
    void prices() {
        SfccPage.Listado l = parse(GRID);

        Product rebajado = tile(l, "PU410189-01").producto();
        assertThat(rebajado.precio()).isEqualTo(84_999.0);
        assertThat(rebajado.precioOriginal()).isEqualTo(139_999.0);

        Product sinTachado = tile(l, "ADKB4379").producto();
        assertThat(sinTachado.precio()).isEqualTo(159_999.0);
        assertThat(sinTachado.precioOriginal()).isNull();
    }

    @Test
    @DisplayName("a price range tile takes its lowest price, the first one listed")
    void priceRange() {
        Product p = tile(parse(GRID), "SCH183241WHT").producto();

        assertThat(p.precio()).isEqualTo(59_999.0);
        assertThat(p.precioOriginal()).isNull();
    }

    @Test
    @DisplayName("a name with an apostrophe still parses the tracking payload that sits in a single-quoted attribute")
    void apostropheInTheTrackingPayload() {
        SfccPage.Tile t = tile(parse(GRID), "NIFB7946-491");

        assertThat(t.producto().nombre()).contains("Studio '72");
    }

    @Test
    @DisplayName("the leaf is the first taxonomy id without its gender; campaign ids are ignored")
    void leafFromTheTaxonomyId() {
        SfccPage.Listado l = parse(GRID);

        assertThat(tile(l, "ADKB4379").hoja()).isEqualTo("indumentaria-camisetas");
        assertThat(tile(l, "PU410189-01").hoja()).isEqualTo("calzado-zapatillas");
        assertThat(tile(l, "PU391327-01").hoja()).isEqualTo("calzado-zapatillas");
        assertThat(tile(l, "ADKB4379").producto().sitioCategoriaOrigenId())
                .isEqualTo(SfccPage.idDeHoja("indumentaria-camisetas"));
    }

    @Test
    @DisplayName("a tile whose five ids carry no taxonomy keeps no leaf and no gender")
    void tileWithoutTaxonomy() {
        SfccPage.Tile t = tile(parse(GRID), "NICN9675-002");

        assertThat(t.hoja()).isNull();
        assertThat(t.producto().sitioCategoriaOrigenId()).isNull();
        assertThat(t.producto().genero()).isEmpty();
    }

    @Test
    @DisplayName("gender: one adult gender, unisex when both, infantil for the kids taxonomy")
    void gender() {
        SfccPage.Listado l = parse(GRID);

        assertThat(tile(l, "PU410189-01").producto().genero()).isEqualTo("hombre");
        assertThat(tile(l, "PU402479-01").producto().genero()).isEqualTo("unisex");
        assertThat(tile(l, "PU391327-01").producto().genero()).isEqualTo("infantil");
        assertThat(SfccPage.genero(Set.of())).isEmpty();
    }

    @Test
    @DisplayName("origin ids are stable and distinct across the leaves of the capture")
    void leafIdsAreStableAndDistinct() {
        List<String> hojas = parse(GRID).tiles().stream().map(SfccPage.Tile::hoja).filter(Objects::nonNull).distinct().toList();

        assertThat(hojas.stream().map(SfccPage::idDeHoja).distinct()).hasSameSizeAs(hojas);
        assertThat(SfccPage.idDeHoja("calzado-zapatillas")).isEqualTo(SfccPage.idDeHoja("calzado-zapatillas")).isPositive();
    }

    @Test
    @DisplayName("accumulating dedups by product id and builds the tree: type nodes, then leaves under them")
    void dedupAndTree() {
        SfccPage page = new SfccPage(null, 1000, "Dexter", BASE + "/", 0, 5_000_000);
        SfccPage.Listado l = parse(GRID);
        List<Product> result = new java.util.ArrayList<>();
        Set<String> vistos = new java.util.HashSet<>();

        page.acumular(l, vistos, result);
        page.acumular(l, vistos, result);

        assertThat(result).hasSize(7);
        var nodos = page.arbolDeCategorias();
        var calzado = nodos.stream().filter(n -> n.origenId() == SfccPage.idDeHoja("calzado")).findFirst().orElseThrow();
        var zapatillas = nodos.stream().filter(n -> n.origenId() == SfccPage.idDeHoja("calzado-zapatillas")).findFirst().orElseThrow();
        assertThat(calzado.padreOrigenId()).isNull();
        assertThat(calzado.nombre()).isEqualTo("Calzado");
        assertThat(zapatillas.padreOrigenId()).isEqualTo(calzado.origenId());
        assertThat(zapatillas.nombre()).isEqualTo("Zapatillas");
        assertThat(nodos).extracting(n -> n.origenId()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the price band drops a tile")
    void priceBand() {
        SfccPage.Listado l = SfccPage.parseListado(GRID, "Dexter", BASE, 100_000, 5_000_000);

        assertThat(l.tarjetas()).isEqualTo(7);
        assertThat(l.tiles()).extracting(SfccPage.Tile::pid).containsExactly("ADKB4379");
    }

    @Test
    @DisplayName("an empty or tile-less answer has zero cards; the crawl turns the first one into SfccPayloadException")
    void emptyAnswer() {
        assertThat(parse("").tarjetas()).isZero();
        assertThat(parse(null).tarjetas()).isZero();
        assertThat(parse("<html lang=\"es\"><head><title>Just a moment...</title></head></html>").tarjetas()).isZero();
    }

    @Test
    @DisplayName("the site id comes from the home page; a home without it throws")
    void siteId() {
        assertThat(SfccPage.siteId("<a href=\"/on/demandware.store/Sites-Dexter-Site/es_AR/Cart-Show\">")).isEqualTo("Dexter");
        assertThatThrownBy(() -> SfccPage.siteId("<html></html>")).isInstanceOf(SfccPayloadException.class);
    }

    @Test
    @DisplayName("the announced total is read from the count span, 0 when it is missing")
    void total() {
        assertThat(SfccPage.total("<span class=\"count\">9940</span> Resultados")).isEqualTo(9940);
        assertThat(SfccPage.total("<html></html>")).isZero();
    }

    private static String fixture(String name) {
        try (InputStream in = SfccPageParseTest.class.getResourceAsStream("/fixtures/sfcc/" + name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
