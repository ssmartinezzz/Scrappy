package ar.scraper.pages;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * KevingstonPage over the recorded page 1 of {@code POST /api/v1/products} (2026-11-10, 36 of 1105 rows).
 * Edge cases mutate real rows of that payload instead of inventing fields.
 */
@Epic("Scraping Engine")
@Feature("Kevingston")
@Story("products JSON to Product mapping, and loud failure on a short or foreign payload")
@DisplayName("KevingstonPage — parseListado / verificarCompleto / cuerpo")
class KevingstonPageParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SITIO = "Kevingston";
    private static final double MIN = 100;
    private static final double MAX = 10_000_000;

    private static ObjectNode payload() {
        try (InputStream in = KevingstonPageParseTest.class
                .getResourceAsStream("/fixtures/kevingston/products-page1.json")) {
            assertThat(in).as("fixture on the classpath").isNotNull();
            return (ObjectNode) MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static ObjectNode fila(ObjectNode root, int i) {
        return (ObjectNode) root.path("data").path("data").get(i);
    }

    private static KevingstonPage.Listado parse(ObjectNode root) {
        return KevingstonPage.parseListado(root.toString(), SITIO, MIN, MAX);
    }

    /** Payload keeping only the first {@code n} rows (counters untouched). */
    private static ObjectNode conFilas(int n) {
        ObjectNode root = payload();
        ArrayNode filas = (ArrayNode) root.path("data").path("data");
        while (filas.size() > n) filas.remove(filas.size() - 1);
        return root;
    }

    // ─── mapping ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the recorded page maps 36 rows, reads the string counters and keeps gender/url/price")
    void recordedPage() {
        KevingstonPage.Listado l = parse(payload());

        assertThat(l.crudos()).isEqualTo(36);
        assertThat(l.productos()).hasSize(36);
        assertThat(l.total()).as("count_datatotal arrives as the string \"1105\"").isEqualTo(1105);
        assertThat(l.paginas()).as("count_page").isEqualTo(31);
        Product p = l.productos().get(0);
        assertThat(p.sitio()).isEqualTo(SITIO);
        assertThat(p.nombre()).isEqualTo("Campera Breck Vintage Modern fit");
        assertThat(p.precio()).isEqualTo(196000.0);
        assertThat(p.url()).isEqualTo("https://www.kevingston.com/producto/053B9001_campera-breck-vintage-modern-fit");
        assertThat(p.genero()).as("idcategory 1").isEqualTo("hombre");
        assertThat(p.categoria()).isEmpty();
        assertThat(p.precioOriginal()).as("preciomax == precio is no discount").isNull();
    }

    @Test
    @DisplayName("image null: the photo comes from the card html data-src, not from the lazy-load gif in src")
    void imageNullFallsBackToDataSrcOverSrc() {
        ObjectNode root = payload();
        assertThat(fila(root, 0).path("image").isNull()).as("fixture row 0 has image=null").isTrue();

        assertThat(parse(root).productos().get(0).imagenUrl())
                .isEqualTo("https://images.kevingston.com/foto-prod/053B9001/xg/053b9001-5.jpg");
    }

    @Test
    @DisplayName("a direct image wins over the html")
    void directImageWins() {
        ObjectNode root = payload();
        assertThat(parse(root).productos().get(1).imagenUrl()).startsWith("https://images.kevingston.com/foto-prod/")
                .isEqualTo(fila(root, 1).path("image").asText());
    }

    @Test
    @DisplayName("blank image and no data-src: the plain src of the html; no <img> at all: empty string")
    void imageSrcAndNone() {
        ObjectNode root = conFilas(2);
        fila(root, 0).put("image", " ").put("html", "<div><img alt=\"x\" src=\"https://c.test/a.jpg\"/></div>");
        fila(root, 1).putNull("image");
        fila(root, 1).put("html", "<div>sin foto</div>");

        var productos = parse(root).productos();

        assertThat(productos.get(0).imagenUrl()).isEqualTo("https://c.test/a.jpg");
        assertThat(productos.get(1).imagenUrl()).isEmpty();
    }

    @ParameterizedTest(name = "idcategory={0} -> genero=''{1}''")
    @CsvSource(value = {"1,hombre", "2,mujer", "3,", "'',"})
    @DisplayName("gender follows the row's section: 1 hombres, 2 mujer, anything else abstains")
    void genderFromSection(String idcategory, String esperado) {
        ObjectNode root = conFilas(1);
        fila(root, 0).put("idcategory", idcategory);

        assertThat(parse(root).productos().get(0).genero()).isEqualTo(esperado == null ? "" : esperado);
    }

    @Test
    @DisplayName("a missing idcategory abstains instead of failing")
    void missingSectionAbstains() {
        ObjectNode root = conFilas(1);
        fila(root, 0).remove("idcategory");

        assertThat(parse(root).productos().get(0).genero()).isEmpty();
    }

    @Test
    @DisplayName("preciomax above precio is the list price; equal or below is not")
    void listPrice() {
        ObjectNode root = conFilas(3);
        fila(root, 0).put("precio", "50000.00").put("preciomax", "80000.00");
        fila(root, 1).put("precio", "50000.00").put("preciomax", "50000.00");
        fila(root, 2).put("precio", "50000.00").put("preciomax", "40000.00");

        var p = parse(root).productos();

        assertThat(p.get(0).precioOriginal()).isEqualTo(80000.0);
        assertThat(p.get(1).precioOriginal()).isNull();
        assertThat(p.get(2).precioOriginal()).isNull();
    }

    // ─── filters ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("stock: only an explicit 0 (or less) is out of stock; null and missing are for sale")
    void stockRules() {
        ObjectNode root = conFilas(5);
        fila(root, 0).put("stock", "0");
        fila(root, 1).putNull("stock");
        fila(root, 2).remove("stock");
        fila(root, 3).put("stock", "-3");
        fila(root, 4).put("stock", "1");

        var l = parse(root);

        assertThat(l.crudos()).as("crudos counts every row the API sent").isEqualTo(5);
        assertThat(l.productos()).extracting(Product::url).containsExactly(
                fila(root, 1).path("url_completa").asText(),
                fila(root, 2).path("url_completa").asText(),
                fila(root, 4).path("url_completa").asText());
    }

    @Test
    @DisplayName("price band is inclusive at both ends; zero, below min and above max are dropped")
    void priceBand() {
        ObjectNode root = conFilas(5);
        fila(root, 0).put("precio", "100.00");
        fila(root, 1).put("precio", "10000000.00");
        fila(root, 2).put("precio", "99.99");
        fila(root, 3).put("precio", "10000000.01");
        fila(root, 4).put("precio", "0");

        assertThat(parse(root).productos()).extracting(Product::url).containsExactly(
                fila(root, 0).path("url_completa").asText(), fila(root, 1).path("url_completa").asText());
    }

    @Test
    @DisplayName("blank name or blank url drops the row; surrounding spaces are trimmed")
    void blankNameOrUrl() {
        ObjectNode root = conFilas(3);
        fila(root, 0).put("nombre", "  ");
        fila(root, 1).put("url_completa", "");
        fila(root, 2).put("nombre", "  Campera Con Espacios ");

        var p = parse(root).productos();

        assertThat(p).hasSize(1);
        assertThat(p.get(0).nombre()).isEqualTo("Campera Con Espacios");
    }

    // ─── failure modes ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] {0}")
    @NullAndEmptySource
    @ValueSource(strings = {"<html>Cloudflare</html>", "{}", "{\"code\":200,\"data\":[]}"})
    @DisplayName("not JSON, empty, null or a foreign shape throws instead of reading as an empty store")
    void foreignPayloadThrows(String json) {
        assertThatThrownBy(() -> KevingstonPage.parseListado(json, SITIO, MIN, MAX))
                .isInstanceOf(KevingstonPayloadException.class)
                .hasMessageContaining("Kevingston");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"count_datatotal", "count_page"})
    @DisplayName("a zero or missing counter throws")
    void zeroCounterThrows(String campo) {
        ObjectNode zero = payload();
        ((ObjectNode) zero.path("data")).put(campo, "0");
        ObjectNode ausente = payload();
        ((ObjectNode) ausente.path("data")).remove(campo);

        assertThatThrownBy(() -> parse(zero)).isInstanceOf(KevingstonPayloadException.class);
        assertThatThrownBy(() -> parse(ausente)).isInstanceOf(KevingstonPayloadException.class);
    }

    @Test
    @DisplayName("data.data not an array throws")
    void rowsNotAnArray() {
        ObjectNode root = payload();
        ((ObjectNode) root.path("data")).put("data", "oops");

        assertThatThrownBy(() -> parse(root)).isInstanceOf(KevingstonPayloadException.class);
    }

    @Test
    @DisplayName("an empty page with a positive total throws: it would end the crawl early")
    void emptyPageThrows() {
        assertThatThrownBy(() -> parse(conFilas(0)))
                .isInstanceOf(KevingstonPayloadException.class)
                .hasMessageContaining("pagina vacia");
    }

    @Test
    @DisplayName("verificarCompleto throws when fewer raw rows arrived than announced, and not at or above it")
    void completenessGuard() {
        assertThatThrownBy(() -> KevingstonPage.verificarCompleto(1104, 1105))
                .isInstanceOf(KevingstonPayloadException.class)
                .hasMessageContaining("1104 de 1105");
        assertThatCode(() -> KevingstonPage.verificarCompleto(1105, 1105)).doesNotThrowAnyException();
        assertThatCode(() -> KevingstonPage.verificarCompleto(1110, 1105)).doesNotThrowAnyException();
    }

    // ─── request ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the request body carries the page number and an empty category (whole catalogue)")
    void requestBody() {
        assertThat(KevingstonPage.cuerpo(1)).startsWith("page=1&category=&");
        assertThat(KevingstonPage.cuerpo(31)).startsWith("page=31&category=&").contains("is_bomba=0");
    }
}
