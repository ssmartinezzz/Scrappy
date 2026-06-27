package ar.scraper.web;

import ar.scraper.aggregator.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@code GET /api/outfits/builder} added to
 * {@link ApiController} by the budget-outfit-builder change.
 *
 * Tests 4.1 — validation paths (400):
 *   - missing/blank categorias
 *   - presupuesto ≤ 0
 *   - more than 10 categories
 *   - all unknown categories
 *
 * Tests 4.2 — success paths (200):
 *   - success JSON shape (slots, total, noCumplePresupuesto:false)
 *   - no-fit JSON shape (slots empty, noCumplePresupuesto:true)
 */
class ApiControllerBuilderTest {

    private ScraperService            service;
    private InflacionService          inflacionService;
    private ScraperConfig             config;
    private ResultAggregator          aggregator;
    private DatabaseService           db;
    private GroupingService           grouping;
    private PythonRunner              pythonRunner;
    private OutfitService             outfitService;
    private RecommendationService     recommendationService;
    private ApiController             controller;

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

        // Stubs used by the feedback-model builder inside outfitsBuilder()
        when(db.obtenerOutfitFeedback()).thenReturn(List.of());
        when(db.obtenerCategoriaDismiss()).thenReturn(Set.of());
    }

    // ── Helper to create a minimal AggregatedResult with the given products ──

    private ResultAggregator.AggregatedResult aggregatedResult(List<Product> productos) {
        return new ResultAggregator.AggregatedResult(
                productos, Map.of(), Map.of(), null, 0, 0);
    }

    // ── Test 4.1 — Validation → HTTP 400 ────────────────────────────────────

    @Test
    void missingCategorias_returns400() {
        ResponseEntity<?> resp = controller.outfitsBuilder(null, 50_000, "hombre");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void blankCategorias_returns400() {
        ResponseEntity<?> resp = controller.outfitsBuilder("   ", 50_000, "hombre");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void presupuestoZero_returns400() {
        ResponseEntity<?> resp = controller.outfitsBuilder("Buzo,Short", 0, "hombre");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void presupuestoNegative_returns400() {
        ResponseEntity<?> resp = controller.outfitsBuilder("Buzo,Short", -1000, "hombre");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void moreThan10ValidCategories_returns400() {
        // 11 canonical categories (all valid, none duplicated)
        String cats = "Buzo,Remera,Camisa,Short,Jean,Zapatilla,Zapatilla Running," +
                      "Gorra,Medias,Mochila,Puffer";
        ResponseEntity<?> resp = controller.outfitsBuilder(cats, 500_000, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void allUnknownCategories_returns400() {
        ResponseEntity<?> resp = controller.outfitsBuilder("Zapato,Vestido,Medias Largas", 50_000, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    // ── Test 4.2 — Success paths → HTTP 200 ──────────────────────────────────

    @Test
    void successResponse_containsExpectedJsonShape() {
        // Stub ScraperService to return a non-null result
        when(service.getLastResult()).thenReturn(aggregatedResult(List.of()));

        // Stub OutfitService to return a 2-slot result
        OutfitService.SlotPick buzoSlot = new OutfitService.SlotPick(
                "Buzo", "Sitio", "Buzo Nike", 20_000,
                "https://test/buzo", "https://img/buzo.jpg", "Buzo", "Nike");
        OutfitService.SlotPick shortSlot = new OutfitService.SlotPick(
                "Short", "Sitio", "Short Adidas", 15_000,
                "https://test/short", "https://img/short.jpg", "Short", "Adidas");
        OutfitService.OutfitBuilderResult stubbedResult = new OutfitService.OutfitBuilderResult(
                List.of(buzoSlot, shortSlot),
                "hombre", 50_000, 35_000,
                false, List.of(), List.of());

        when(outfitService.armarPorCategorias(anyList(), anyList(), anyDouble(), anyString(), any()))
                .thenReturn(stubbedResult);

        ResponseEntity<?> resp = controller.outfitsBuilder("Buzo,Short", 50_000, "hombre");

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("slots").isArray()).isTrue();
        assertThat(body.path("slots").size()).isEqualTo(2);
        assertThat(body.path("totalEstimado").asDouble()).isEqualTo(35_000);
        assertThat(body.path("noCumplePresupuesto").asBoolean()).isFalse();
        assertThat(body.path("presupuesto").asDouble()).isEqualTo(50_000);
        assertThat(body.path("categoriasVacias").isArray()).isTrue();
        assertThat(body.path("categoriasSinPresupuesto").isArray()).isTrue();
    }

    @Test
    void noFitResponse_hasEmptySlotsAndNoCumplePresupuestoTrue() {
        when(service.getLastResult()).thenReturn(aggregatedResult(List.of()));

        OutfitService.OutfitBuilderResult noFitResult = new OutfitService.OutfitBuilderResult(
                List.of(), "hombre", 5_000, 0.0,
                true, List.of(), List.of("Buzo"));

        when(outfitService.armarPorCategorias(anyList(), anyList(), anyDouble(), anyString(), any()))
                .thenReturn(noFitResult);

        ResponseEntity<?> resp = controller.outfitsBuilder("Buzo", 5_000, "hombre");

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("slots").size()).isEqualTo(0);
        assertThat(body.path("noCumplePresupuesto").asBoolean()).isTrue();
        assertThat(body.path("totalEstimado").asDouble()).isEqualTo(0.0);
    }
}
