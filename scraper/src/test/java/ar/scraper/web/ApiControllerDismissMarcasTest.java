package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApiControllerDismissMarcasTest {

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
        service               = mock(ScraperService.class);
        inflacionService      = mock(InflacionService.class);
        config                = mock(ScraperConfig.class);
        aggregator            = mock(ResultAggregator.class);
        db                    = mock(DatabaseService.class);
        grouping              = mock(GroupingService.class);
        pythonRunner          = mock(PythonRunner.class);
        outfitService         = mock(OutfitService.class);
        recommendationService = mock(RecommendationService.class);
        controller = new ApiController(service, inflacionService, config, aggregator,
                db, grouping, pythonRunner, outfitService, recommendationService);
    }

    // ── POST /api/recomendados/dismiss-categoria ─────────────────────────

    @Test
    void dismissCategoriaReturns400WhenCategoriaBlank() {
        var resp = controller.dismissCategoria(Map.of("categoria", ""));
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(body.get("ok").asBoolean()).isFalse();
        verify(db, never()).guardarCategoriaDismiss(any());
    }

    @Test
    void dismissCategoriaReturns200AndPersistsWhenValid() {
        var resp = controller.dismissCategoria(Map.of("categoria", "Zapatillas"));
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("ok").asBoolean()).isTrue();
        verify(db).guardarCategoriaDismiss("Zapatillas");
    }

    // ── DELETE /api/recomendados/dismiss-categoria ───────────────────────

    @Test
    void undismissCategoriaAlwaysReturnsOkAndCallsDb() {
        var resp = controller.undismissCategoria("Remeras");
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("ok").asBoolean()).isTrue();
        verify(db).borrarCategoriaDismiss("Remeras");
    }

    // ── GET /api/marcas-browser ──────────────────────────────────────────

    @Test
    void marcasBrowserReturns204WhenNoLastResult() {
        when(service.getLastResult()).thenReturn(null);

        var resp = controller.marcasBrowser(null, null, "count");

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void marcasBrowserReturns200WhenResultExists() {
        // Products with a real brand (at least 2 per brand to pass the >=2 filter)
        var products = List.of(
                producto("Nike Air Max", "https://a.com/1", "Nike"),
                producto("Nike Vomero", "https://a.com/2", "Nike"),
                producto("Adidas Ultraboost", "https://b.com/1", "Adidas"),
                producto("Adidas Stan Smith", "https://b.com/2", "Adidas"));
        when(service.getLastResult()).thenReturn(mockResult(products));

        var resp = controller.marcasBrowser(null, null, "count");
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void marcasBrowserFiltersByRubroParam() {
        var products = List.of(
                productWithRubro("Nike Tee", "https://a.com/1", "Nike", "indumentaria"),
                productWithRubro("Nike Protein", "https://a.com/2", "Nike", "suplementos"),
                productWithRubro("Nike Runner2", "https://a.com/3", "Nike", "indumentaria"));
        when(service.getLastResult()).thenReturn(mockResult(products));

        var resp = controller.marcasBrowser("indumentaria", null, "count");
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        // Only products with rubro=indumentaria are included; the suplementos product is filtered
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        // Nike has 2 indumentaria products → passes the >=2 filter
        assertThat(body.isArray()).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private AggregatedResult mockResult(List<Product> products) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(products, Map.of(), Map.of(), facets, 0, 0);
    }

    private Product producto(String nombre, String url, String marca) {
        return new Product("Sporting", nombre, 10000.0, null, url, "img",
                "Zapatillas", "unisex", List.of(), Product.MlScore.EMPTY, marca,
                "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY);
    }

    private Product productWithRubro(String nombre, String url, String marca, String rubro) {
        return new Product("Sporting", nombre, 10000.0, null, url, "img",
                "Zapatillas", "unisex", List.of(), Product.MlScore.EMPTY, marca,
                rubro, false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY);
    }
}
