package ar.scraper.db;

import ar.scraper.aggregator.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import ar.scraper.model.Product.MlScore;
import ar.scraper.model.Product.SenalFinanciacion;
import ar.scraper.web.ApiController;
import ar.scraper.web.InflacionService;
import ar.scraper.web.OutfitService;
import ar.scraper.web.RecommendationService;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the "Para ti" feed ({@code GET /api/recomendados}, serialized by
 * {@code escribirProducto}) exposes pack unit-price metadata — {@code esPack},
 * {@code cantidadUnidades}, {@code precioUnitario} — so {@code ProductCard}
 * renders a pack's per-unit price instead of only the (misleadingly high) shelf
 * price. Parity with the catalog ({@code /api/data}) and Mejores Picks
 * ({@code /api/mejores}) which already emit these fields.
 *
 * <p>Lives in {@code ar.scraper.db} (like
 * {@code ApiControllerRecomendadosBidirectionalTest}) to use a REAL temp-file
 * {@link DatabaseService}, because {@code recomendados} reads feedback/dismiss
 * state from the DB.</p>
 */
class ApiControllerRecomendadosPackFieldsTest {

    @TempDir
    Path tempDir;

    private DatabaseService db;
    private ScraperService service;
    private ApiController controller;

    private Product packProducto(String url, double precio, int cantidadUnidades) {
        return new Product("Sitio", "Producto " + url, precio, null, url, "img",
                "Medias", "hombre", List.of(), MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY,
                cantidadUnidades, "");
    }

    private AggregatedResult resultWith(Product p) {
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(List.of(p));
        return result;
    }

    private JsonNode firstItem(ResponseEntity<ObjectNode> resp) {
        return resp.getBody().get("items").get(0);
    }

    @BeforeEach
    void setUp() {
        db = new DatabaseService();
        db.initEn(tempDir.resolve("test-reco-pack.db").toString());

        service = mock(ScraperService.class);
        InflacionService inflacionService = mock(InflacionService.class);
        ScraperConfig config              = mock(ScraperConfig.class);
        ResultAggregator aggregator       = mock(ResultAggregator.class);
        GroupingService grouping          = mock(GroupingService.class);
        PythonRunner pythonRunner         = mock(PythonRunner.class);
        RecommendationService recommendationService = new RecommendationService();
        OutfitService outfitService       = new OutfitService(recommendationService);

        controller = new ApiController(service, inflacionService, config, aggregator,
                db, grouping, pythonRunner, outfitService, recommendationService);
    }

    @AfterEach
    void tearDown() {
        db.cerrar();
    }

    @Test
    void packItemExposesUnitPriceFields() {
        Product pack = packProducto("https://t/pack-medias-x3", 15000, 3);
        AggregatedResult result = resultWith(pack);
        when(service.getLastResult()).thenReturn(result);

        ResponseEntity<ObjectNode> resp = controller.recomendados(1, 24, null, null);

        JsonNode item = firstItem(resp);
        assertThat(item.path("esPack").asBoolean()).isTrue();
        assertThat(item.path("cantidadUnidades").asInt()).isEqualTo(3);
        assertThat(item.path("precioUnitario").asDouble()).isEqualTo(5000.0, offset(0.001));
        assertThat(item.path("precio").asDouble()).isEqualTo(15000.0, offset(0.001));
    }

    @Test
    void nonPackItemUnitPriceEqualsShelfPrice() {
        Product single = packProducto("https://t/single", 9000, 1);
        AggregatedResult result = resultWith(single);
        when(service.getLastResult()).thenReturn(result);

        ResponseEntity<ObjectNode> resp = controller.recomendados(1, 24, null, null);

        JsonNode item = firstItem(resp);
        assertThat(item.path("esPack").asBoolean()).isFalse();
        assertThat(item.path("cantidadUnidades").asInt()).isEqualTo(1);
        assertThat(item.path("precioUnitario").asDouble())
                .isEqualTo(item.path("precio").asDouble(), offset(0.001));
        assertThat(item.path("precioUnitario").asDouble()).isEqualTo(9000.0, offset(0.001));
    }
}
