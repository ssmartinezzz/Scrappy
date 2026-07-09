package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import ar.scraper.model.Product.MlScore;
import ar.scraper.model.Product.SenalFinanciacion;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for pack unit-price awareness in {@code GET /api/mejores}
 * (mejores-picks-pack-unit-pricing). Mirrors
 * {@code ApiControllerMejoresInfantilVetoTest}'s Mockito-mock constructor
 * convention.
 *
 * <p>Spec requirements: category median in {@code mejoresPorCategoria} MUST be
 * computed on unit price (precio / cantidadUnidades for packs), and every pick
 * payload MUST expose {@code esPack}, {@code cantidadUnidades} and
 * {@code precioUnitario} alongside the existing shelf {@code precio}.</p>
 */
@Epic("REST API")
@Feature("Mejores Picks / Recomendados")
@Story("Pack unit price")
@DisplayName("ApiController — Mejores Picks pack unit price")
class ApiControllerMejoresPackUnitPriceTest {

    private ScraperService service;
    private InflacionService inflacionService;
    private ScraperConfig config;
    private ResultAggregator aggregator;
    private DatabaseService db;
    private GroupingService grouping;
    private PythonRunner pythonRunner;
    private OutfitService outfitService;
    private RecommendationService recommendationService;
    private ApiController controller;

    @BeforeEach
    void setUp() {
        wireController();
    }

    @Step("Wire ApiController with mocked collaborators")
    private void wireController() {
        service          = mock(ScraperService.class);
        inflacionService = mock(InflacionService.class);
        config            = mock(ScraperConfig.class);
        aggregator        = mock(ResultAggregator.class);
        db                = mock(DatabaseService.class);
        grouping          = mock(GroupingService.class);
        pythonRunner      = mock(PythonRunner.class);
        outfitService     = mock(OutfitService.class);
        recommendationService = mock(RecommendationService.class);
        controller = new ApiController(service, inflacionService, config, aggregator,
                db, grouping, pythonRunner, outfitService, recommendationService);
    }

    /** Non-pack product (cantidadUnidades = 1). */
    private Product producto(String url, double precio) {
        return new Product("Sitio", "Producto " + url, precio, null, url, "img",
                "Zapatillas", "hombre", List.of(), MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1, "");
    }

    /** Product with an explicit cantidadUnidades (pack when > 1). */
    private Product producto(String url, double precio, int cantidadUnidades) {
        return new Product("Sitio", "Producto " + url, precio, null, url, "img",
                "Zapatillas", "hombre", List.of(), MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY,
                cantidadUnidades, "");
    }

    /** Product with an explicit MlScore and cantidadUnidades, for slot-winning scenarios. */
    private Product producto(String url, double precio, int cantidadUnidades, MlScore ml) {
        return new Product("Sitio", "Producto " + url, precio, null, url, "img",
                "Zapatillas", "hombre", List.of(), ml, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY,
                cantidadUnidades, "");
    }

    private AggregatedResult resultFor(Product... productos) {
        List<Product> lista = List.of(productos);
        double min = lista.stream().mapToDouble(Product::precio).min().orElse(0);
        double max = lista.stream().mapToDouble(Product::precio).max().orElse(0);
        return new AggregatedResult(lista, Map.of("Sitio", lista.size()), Map.of(),
                ResultAggregator.calcularFacets(lista), min, max);
    }

    private JsonNode zapatillasNode(ResponseEntity<Object> resp) {
        JsonNode body = (JsonNode) resp.getBody();
        for (JsonNode node : body) {
            if ("Zapatillas".equals(node.path("categoria").asText())) return node;
        }
        return null;
    }

    // ── precioUnitario(Product) helper ──────────────────────────────────────

    @Test
    void precioUnitario_packDivides() {
        Product pack = producto("https://site.com/pack", 15000, 3);
        assertThat(ApiController.precioUnitario(pack)).isEqualTo(5000.0, offset(0.001));
    }

    @Test
    void precioUnitario_unidades1EqualsPrice() {
        Product single = producto("https://site.com/single", 9000, 1);
        assertThat(ApiController.precioUnitario(single)).isEqualTo(9000.0, offset(0.001));
    }

    @Test
    void precioUnitario_unidades0FallsBackToShelfPrice() {
        Product zero = producto("https://site.com/zero", 9000, 0);
        assertThat(ApiController.precioUnitario(zero)).isEqualTo(9000.0, offset(0.001));
    }

    // ── Median computed on unit price ───────────────────────────────────────

    @Test
    void median_computedOnUnitPriceNotShelfPrice() {
        Product a    = producto("https://site.com/a", 8000);
        Product b    = producto("https://site.com/b", 9000);
        Product c    = producto("https://site.com/c", 10000);
        Product pack = producto("https://site.com/pack", 15000, 3); // unit = 5000
        when(service.getLastResult()).thenReturn(resultFor(a, b, c, pack));

        ResponseEntity<Object> resp = controller.mejoresPorCategoria(null);

        JsonNode node = zapatillasNode(resp);
        assertThat(node).isNotNull();
        // Unit prices sorted: 5000, 8000, 9000, 10000 -> skip(4/2=2) -> 9000
        // (mirrors the pre-existing upper-middle "sorted().skip(size/2)" selection,
        // now applied over unit prices instead of shelf prices)
        assertThat(node.path("mediana").asLong()).isEqualTo(9000L);
    }

    @Test
    void median_allPacksCategory_validNonNull() {
        Product p1 = producto("https://site.com/p1", 15000, 3); // unit 5000
        Product p2 = producto("https://site.com/p2", 20000, 4); // unit 5000
        Product p3 = producto("https://site.com/p3", 30000, 2); // unit 15000
        when(service.getLastResult()).thenReturn(resultFor(p1, p2, p3));

        ResponseEntity<Object> resp = controller.mejoresPorCategoria(null);

        JsonNode node = zapatillasNode(resp);
        assertThat(node).isNotNull();
        assertThat(node.path("mediana").isMissingNode()).isFalse();
        assertThat(node.path("mediana").asLong()).isGreaterThan(0L);
    }

    // ── Pick payload composition ────────────────────────────────────────────

    @Test
    void pickPayload_packIncludesEsPackCantidadPrecioUnitario() {
        MlScore goodScore = new MlScore(10, "", false, "estable", 10);
        Product pack = producto("https://site.com/pack", 15000, 3, goodScore);
        when(service.getLastResult()).thenReturn(resultFor(pack));

        ResponseEntity<Object> resp = controller.mejoresPorCategoria(null);

        JsonNode node = zapatillasNode(resp);
        assertThat(node).isNotNull();
        JsonNode valorPick = node.path("picks").get(0);
        assertThat(valorPick.path("esPack").asBoolean()).isTrue();
        assertThat(valorPick.path("cantidadUnidades").asInt()).isEqualTo(3);
        assertThat(valorPick.path("precioUnitario").asDouble()).isEqualTo(5000.0, offset(0.001));
        assertThat(valorPick.path("precio").asDouble()).isEqualTo(15000.0, offset(0.001));
    }

    @Test
    void pickPayload_nonPackFieldsInertAndByteIdentical() {
        Product single = producto("https://site.com/single", 9000);
        when(service.getLastResult()).thenReturn(resultFor(single));

        ResponseEntity<Object> resp = controller.mejoresPorCategoria(null);

        JsonNode node = zapatillasNode(resp);
        assertThat(node).isNotNull();
        JsonNode valorPick = node.path("picks").get(0);
        assertThat(valorPick.path("esPack").asBoolean()).isFalse();
        assertThat(valorPick.path("cantidadUnidades").asInt()).isEqualTo(1);
        assertThat(valorPick.path("precioUnitario").asDouble())
                .isEqualTo(valorPick.path("precio").asDouble(), offset(0.001));
        assertThat(valorPick.path("precio").asDouble()).isEqualTo(9000.0, offset(0.001));
        assertThat(valorPick.path("nombre").asText()).isEqualTo("Producto https://site.com/single");
    }

    // ── Pack slot competitiveness ───────────────────────────────────────────

    @Test
    void pack_winsSlotOnUnitPriceItWouldLoseOnShelfPrice() {
        // Category unit-price median ~8000; pack shelf price (15000) is above what a
        // shelf-based median would be, but its unit price (5000) is well below the
        // unit-price median. The ML pipeline (out of scope, already unit-price-aware
        // per ml_pipeline.py) would score such a pack favorably; simulate that here
        // with a low scoreP so the pack naturally wins the "valor" (min scoreP) slot.
        MlScore packScore = new MlScore(5, "", false, "estable", 5);
        MlScore mediocreScore = new MlScore(80, "", false, "estable", 80);
        Product a    = producto("https://site.com/a", 8000, 1, mediocreScore);
        Product b    = producto("https://site.com/b", 9000, 1, mediocreScore);
        Product c    = producto("https://site.com/c", 10000, 1, mediocreScore);
        Product pack = producto("https://site.com/pack", 15000, 3, packScore); // unit 5000

        List<Product> withImages = List.of(
                withImage(a), withImage(b), withImage(c), withImage(pack));
        when(service.getLastResult()).thenReturn(resultFor(withImages.toArray(new Product[0])));

        ResponseEntity<Object> resp = controller.mejoresPorCategoria(null);

        JsonNode node = zapatillasNode(resp);
        assertThat(node).isNotNull();
        JsonNode valorPick = node.path("picks").get(0);
        assertThat(valorPick.path("url").asText()).isEqualTo("https://site.com/pack");
        assertThat(valorPick.path("esPack").asBoolean()).isTrue();
        assertThat(valorPick.path("precioUnitario").asDouble()).isEqualTo(5000.0, offset(0.001));
        assertThat(valorPick.path("precioUnitario").asDouble())
                .isLessThan(node.path("mediana").asDouble());
    }

    @Test
    void cantidadUnidades1OrMissing_slotAssignmentUnchanged() {
        MlScore bestScore = new MlScore(5, "", false, "estable", 5);
        Product best = producto("https://site.com/best", 8000, 1, bestScore);
        Product other = producto("https://site.com/other", 9000);
        when(service.getLastResult()).thenReturn(resultFor(best, other));

        ResponseEntity<Object> resp = controller.mejoresPorCategoria(null);

        JsonNode node = zapatillasNode(resp);
        assertThat(node).isNotNull();
        JsonNode valorPick = node.path("picks").get(0);
        assertThat(valorPick.path("url").asText()).isEqualTo("https://site.com/best");
        assertThat(valorPick.path("esPack").asBoolean()).isFalse();
        assertThat(valorPick.path("precioUnitario").asDouble())
                .isEqualTo(valorPick.path("precio").asDouble(), offset(0.001));
    }

    @Test
    void categoryReturnsUpToTenPicksAndIncludesMidRankedPack() {
        // 12 non-pack singles with ascending scoreP (1..12) + a pack scored 6 by its
        // unit price. The pack is NOT the single best value, but sits comfortably
        // within the 10 best by scoreP → it must surface among the picks (the whole
        // point: packs integrate into Mejores Picks, not a separate view).
        java.util.List<Product> productos = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            productos.add(withImage(producto(
                    "https://site.com/single-" + i, 10000, 1,
                    new MlScore(i + 1, "", false, "estable", i + 1))));
        }
        Product pack = withImage(producto("https://site.com/pack", 15000, 3,
                new MlScore(6, "", false, "estable", 6))); // unit 5000, scoreP 6
        productos.add(pack);
        when(service.getLastResult()).thenReturn(resultFor(productos.toArray(new Product[0])));

        ResponseEntity<Object> resp = controller.mejoresPorCategoria(null);

        JsonNode node = zapatillasNode(resp);
        assertThat(node).isNotNull();
        JsonNode picks = node.path("picks");
        // Capped at 10 per category even though 13 products qualify.
        assertThat(picks.size()).isEqualTo(10);
        boolean packPresent = false;
        for (JsonNode pk : picks) {
            if ("https://site.com/pack".equals(pk.path("url").asText())) packPresent = true;
        }
        assertThat(packPresent).isTrue();
    }

    /** Attaches a non-blank imagenUrl, required for the "valor" pick's min-scoreP filter. */
    private Product withImage(Product p) {
        return new Product(p.sitio(), p.nombre(), p.precio(), p.precioOriginal(), p.url(),
                "https://img.example.com/x.jpg", p.categoria(), p.genero(), p.talles(), p.ml(),
                p.marca(), p.rubro(), p.gymrat(), p.marcaPremium(), p.senal(), p.finan(),
                p.cantidadUnidades(), p.subCategoria());
    }
}
