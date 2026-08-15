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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fix-zero-yield-tech-sites, C2 (spec: {@code tech-site-catalog-coverage} /
 * Compragamer Reads Its Static JSON Feed, design D3).
 *
 * <p>{@code TechStorePage.parseCompraGamerFeed} replaces the old React-SPA
 * HTML scrape ({@code discoverCompraGamerCates}/{@code extractCompraGamer},
 * both deleted in this commit) with a read of the store's own static JSON
 * feed. Fixtures in {@code fixtures/compragamer/} are a trimmed, mostly
 * verbatim capture of the live payload — see {@code SOURCE.md} there for the
 * capture date and which items are synthetic edge cases.</p>
 */
@Epic("Scraping Engine")
@Feature("Tech stores")
@Story("Compragamer reads static.compragamer.com/productos instead of scraping HTML")
@DisplayName("TechStorePage.parseCompraGamerFeed — Compragamer JSON feed")
class CompraGamerFeedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE_URL = "https://compragamer.com";
    private static final double MIN = 1000;
    private static final double MAX = 1_000_000;

    private static final String PRODUCTOS = readFixture("productos.json");
    private static final String CATEGORIAS_SUB = readFixture("categorias_sub.json");
    private static final String MARCAS = readFixture("marcas.json");

    @Test
    @DisplayName("un feed con forma real, sintetizado a 1200 items validos, rinde >= 1000")
    void syntheticThousandItemFeedYieldsAtLeastAThousand() {
        String bigFeed = synthesizeValidFeed(1200);
        List<Product> result = TechStorePage.parseCompraGamerFeed(
                bigFeed, CATEGORIAS_SUB, MARCAS, "Compragamer", BASE_URL, MIN, MAX);
        assertThat(result.size()).isGreaterThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("regresion: el feed de fixture ya no rinde 0 (era 0 en el HTML scrape viejo)")
    void fixtureFeedRegressionYieldsMoreThanZero() {
        List<Product> result = parseFixture();
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("vendible=0 y stock=0 excluyen el item, cada uno de forma independiente")
    void excludesUnsellableItemsByEitherCondition() {
        List<Product> result = parseFixture();
        List<String> urls = result.stream().map(Product::url).toList();

        assertThat(urls).as("id_producto=14895 tiene vendible=0")
                .noneMatch(u -> u.contains("14895"));
        assertThat(urls).as("id_producto=20001 tiene stock=0")
                .noneMatch(u -> u.contains("20001"));
    }

    @Test
    @DisplayName("precio fuera de [min,max] excluye el item")
    void excludesOutOfPriceBoundItems() {
        List<Product> result = parseFixture();
        List<String> urls = result.stream().map(Product::url).toList();

        assertThat(urls).as("id_producto=20002 vale $50, bajo el minimo de $1000")
                .noneMatch(u -> u.contains("20002"));
        assertThat(urls).as("id_producto=20003 vale $5.000.000, sobre el maximo de $1.000.000")
                .noneMatch(u -> u.contains("20003"));
    }

    @Test
    @DisplayName("es_outlet=true NO es un filtro — el item sigue incluido")
    void outletItemsAreNotFiltered() {
        List<Product> result = parseFixture();
        assertThat(result.stream().map(Product::url))
                .anyMatch(u -> u.contains("20004"));
    }

    @Test
    @DisplayName("precio = precioEspecial, precioOriginal = precioLista cuando precioLista > precio")
    void mapsPrecioOriginalFromPrecioListaWhenGreater() {
        Product estabilizador = productoPorId(parseFixture(), 1674);
        assertThat(estabilizador.precio()).isEqualTo(44990.0);
        assertThat(estabilizador.precioOriginal()).isEqualTo(49989.0);
    }

    @Test
    @DisplayName("precioOriginal cae a precioListaAnterior cuando precioLista NO es mayor que precio")
    void mapsPrecioOriginalFromPrecioListaAnteriorWhenPrecioListaIsNotGreater() {
        Product placaDeRed = productoPorId(parseFixture(), 3903);
        assertThat(placaDeRed.precio()).isEqualTo(6100.0);
        assertThat(placaDeRed.precioOriginal()).isEqualTo(13556.0);
    }

    @Test
    @DisplayName("categoria: id_subcategoria primero, id_categoria como fallback, '' si ninguno mapea")
    void mapsCategoriaFromSubcategoriaThenCategoriaThenAbstains() {
        List<Product> result = parseFixture();

        assertThat(productoPorId(result, 1674).categoria()).isEqualTo("Estabilizadores");
        assertThat(productoPorId(result, 20006).categoria())
                .as("id_subcategoria=9999 no mapea, cae a id_categoria=5")
                .isEqualTo("Monitores y pantallas");
        assertThat(productoPorId(result, 20005).categoria())
                .as("ni id_subcategoria=9999 ni id_categoria=8888 mapean")
                .isEmpty();
    }

    @Test
    @DisplayName("imagen: primer nombre de imagenes[] ordenado por orden asc, o '' si imagenes es null")
    void mapsImageFromFirstSortedImagen() {
        List<Product> result = parseFixture();

        // El `nombre` del feed es la CLAVE de la imagen, no su URL. La ruta real
        // del bucket, confirmada en vivo (2026-08-15) contra las <img> que el
        // sitio renderiza y con HEAD 200 sobre la URL construida, es
        // /productos/compragamer_Imganen_general_{nombre}.jpg — con el typo
        // "Imganen" incluido, que es de ellos. Sin ese prefijo el bucket S3
        // responde 403 AccessDenied para el 100% de los productos.
        assertThat(productoPorId(result, 1674).imagenUrl())
                .isEqualTo("https://imagenes.compragamer.com/productos/"
                        + "compragamer_Imganen_general_1226_Estabilizador_Desktop_TCA-1200N_LYONN_AV_63a535eb.jpg");
        assertThat(productoPorId(result, 2113).imagenUrl()).isEmpty();
    }

    @Test
    @DisplayName("url de producto: /producto/{slug}_{id} — /producto/{id} pelado redirige al home")
    void buildsProductUrlWithTheSlugIdSuffixTheRouterNeeds() {
        // Medido en vivo (2026-08-15): la SPA rutea por el sufijo `_{id}` del
        // ultimo segmento. /producto/1674 (sin `_`) no resuelve y el router
        // redirige al home — o sea que TODA fila de Compragamer guardaba un link
        // muerto, y la url es la PK del catalogo.
        assertThat(productoPorId(parseFixture(), 1674).url())
                .isEqualTo("https://compragamer.com/producto/Estabilizador_Desktop_TCA_1200N_LYONN_AV_1674");
    }

    @Test
    @DisplayName("el slug colapsa cada corrida de no-alfanumericos en UN solo guion bajo")
    void collapsesNonAlphanumericRunsIntoASingleUnderscore() {
        // "Tp-Link TG-3468" -> "Tp_Link_TG_3468": el separador es la corrida
        // entera, no cada caracter (asi arma sus propios links el sitio).
        assertThat(productoPorId(parseFixture(), 3903).url())
                .isEqualTo("https://compragamer.com/producto/"
                        + "Placa_de_Red_Tp_Link_TG_3468_Ethernet_PCIe_1000Mbps_3903");
    }

    @Test
    @DisplayName("marca NUNCA se escribe (fk_productos_marca, CODE-6 — BrandExtractor es el dueño)")
    void neverWritesMarca() {
        List<Product> result = parseFixture();
        assertThat(result).isNotEmpty();
        assertThat(result).allSatisfy(p -> assertThat(p.marca()).isEmpty());
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static List<Product> parseFixture() {
        return TechStorePage.parseCompraGamerFeed(
                PRODUCTOS, CATEGORIAS_SUB, MARCAS, "Compragamer", BASE_URL, MIN, MAX);
    }

    /** La url termina en {@code _{id}} — el sufijo por el que rutea la SPA. */
    private static Product productoPorId(List<Product> productos, int idProducto) {
        return productos.stream()
                .filter(p -> p.url().endsWith("_" + idProducto))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No se encontro id_producto=" + idProducto + " en el resultado"));
    }

    /** Synthesizes N valid, in-bounds items with the same field shape the real feed uses. */
    private static String synthesizeValidFeed(int n) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (int i = 0; i < n; i++) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("id_producto", 900000 + i);
            item.put("nombre", "Producto Sintetico " + i);
            item.put("id_categoria", 5);
            item.put("id_subcategoria", 31);
            item.put("id_marca", 63);
            item.put("precioLista", 20000);
            item.put("precioEspecial", 18000);
            item.putNull("precioListaAnterior");
            item.putNull("imagenes");
            item.put("stock", 5);
            item.put("vendible", 1);
            item.put("es_outlet", false);
            arr.add(item);
        }
        return arr.toString();
    }

    private static String readFixture(String name) {
        String path = "/fixtures/compragamer/" + name;
        try (InputStream in = CompraGamerFeedTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
