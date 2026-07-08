package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
 *
 * Tests 4.3 — new params (UOB-10, UOB-11):
 *   - excluir CSV param parsed and passed as Set
 *   - greedy=true triggers greedy path
 *   - minimoBudgetNecesario in no-fit JSON response
 *   - minimoBudgetNecesario absent/null on success
 */
@Epic("REST API")
@Feature("Sitios / Config / Wiring")
@Story("Controller wiring")
@DisplayName("ApiController — Outfit builder endpoint")
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
        wireController();

        // Stubs used by the feedback-model builder inside outfitsBuilder()
        when(db.obtenerOutfitFeedback()).thenReturn(List.of());
        when(db.obtenerCategoriaDismiss()).thenReturn(Set.of());
    }

    @Step("Wire ApiController with mocked collaborators")
    private void wireController() {
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

    // ── Helper to create a minimal AggregatedResult with the given products ──

    private ResultAggregator.AggregatedResult aggregatedResult(List<Product> productos) {
        return new ResultAggregator.AggregatedResult(
                productos, Map.of(), Map.of(), null, 0, 0);
    }

    // ── Test 4.1 — Validation → HTTP 400 ────────────────────────────────────

    @Test
    void missingCategorias_returns400() {
        ResponseEntity<?> resp = controller.outfitsBuilder(null, 50_000, "hombre", "", "", false, "gym");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void blankCategorias_returns400() {
        ResponseEntity<?> resp = controller.outfitsBuilder("   ", 50_000, "hombre", "", "", false, "gym");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void presupuestoZero_returns400() {
        Allure.parameter("presupuesto", 0);
        ResponseEntity<?> resp = controller.outfitsBuilder("Buzo,Short", 0, "hombre", "", "", false, "gym");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void presupuestoNegative_returns400() {
        Allure.parameter("presupuesto", -1000);
        ResponseEntity<?> resp = controller.outfitsBuilder("Buzo,Short", -1000, "hombre", "", "", false, "gym");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void moreThan20ValidCategories_returns400() {
        // 21 canonical categories (all valid, none duplicated) — exceeds the max-20 guard
        String cats = "Buzo,Remera,Camisa,Short,Jean,Zapatilla,Zapatilla Running," +
                      "Gorra,Medias,Mochila,Puffer,Campera,Sweater,Calza,Baggy," +
                      "Jogging,Bermuda,Pollera,Sneaker,Botines,Borcego";
        ResponseEntity<?> resp = controller.outfitsBuilder(cats, 500_000, null, "", "", false, "gym");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void allUnknownCategories_returns400() {
        ResponseEntity<?> resp = controller.outfitsBuilder("Zapato,Vestido,Medias Largas", 50_000, null, "", "", false, "gym");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    // ── Test 4.2 — Success paths → HTTP 200 ──────────────────────────────────

    @Test
    void successResponse_containsExpectedJsonShape() {
        when(service.getLastResult()).thenReturn(aggregatedResult(List.of()));

        OutfitService.SlotPick buzoSlot = new OutfitService.SlotPick(
                "Buzo", "Sitio", "Buzo Nike", 20_000,
                "https://test/buzo", "https://img/buzo.jpg", "Buzo", "Nike");
        OutfitService.SlotPick shortSlot = new OutfitService.SlotPick(
                "Short", "Sitio", "Short Adidas", 15_000,
                "https://test/short", "https://img/short.jpg", "Short", "Adidas");
        OutfitService.OutfitBuilderResult stubbedResult = new OutfitService.OutfitBuilderResult(
                List.of(buzoSlot, shortSlot),
                "hombre", 50_000, 35_000,
                false, List.of(), List.of(), null);

        when(outfitService.armarPorCategorias(anyList(), anyList(), anyDouble(), anyString(), any(), anySet(), anyBoolean(), anyList(), anyString()))
                .thenReturn(stubbedResult);

        ResponseEntity<?> resp = controller.outfitsBuilder("Buzo,Short", 50_000, "hombre", "", "", false, "gym");

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("status").asText()).isEqualTo("ok");
        assertThat(body.path("slots").isArray()).isTrue();
        assertThat(body.path("slots").size()).isEqualTo(2);
        assertThat(body.path("totalEstimado").asDouble()).isEqualTo(35_000);
        assertThat(body.path("noCumplePresupuesto").asBoolean()).isFalse();
        assertThat(body.path("presupuesto").asDouble()).isEqualTo(50_000);
        assertThat(body.path("categoriasVacias").isArray()).isTrue();
        assertThat(body.path("categoriasSinPresupuesto").isArray()).isTrue();
        assertThat(body.has("reason")).isFalse();
    }

    @Test
    void noFitResponse_hasEmptySlotsAndNoCumplePresupuestoTrue() {
        when(service.getLastResult()).thenReturn(aggregatedResult(List.of()));

        OutfitService.OutfitBuilderResult noFitResult = new OutfitService.OutfitBuilderResult(
                List.of(), "hombre", 5_000, 0.0,
                true, List.of(), List.of("Buzo"), null);

        when(outfitService.armarPorCategorias(anyList(), anyList(), anyDouble(), anyString(), any(), anySet(), anyBoolean(), anyList(), anyString()))
                .thenReturn(noFitResult);

        ResponseEntity<?> resp = controller.outfitsBuilder("Buzo", 5_000, "hombre", "", "", false, "gym");

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("status").asText()).isEqualTo("no-fit");
        assertThat(body.path("slots").size()).isEqualTo(0);
        assertThat(body.path("noCumplePresupuesto").asBoolean()).isTrue();
        assertThat(body.path("totalEstimado").asDouble()).isEqualTo(0.0);
        assertThat(body.path("reason").asText()).isNotBlank();
    }

    // ── Test 4.3 — New params: excluir, greedy, minimoBudgetNecesario ────────

    @Test
    void excluirParamParsedAndPassedThrough() {
        when(service.getLastResult()).thenReturn(aggregatedResult(List.of()));

        OutfitService.OutfitBuilderResult stub = new OutfitService.OutfitBuilderResult(
                List.of(), "hombre", 50_000, 0.0, false, List.of(), List.of(), null);
        when(outfitService.armarPorCategorias(anyList(), anyList(), anyDouble(), anyString(), any(), anySet(), anyBoolean(), anyList(), anyString()))
                .thenReturn(stub);

        controller.outfitsBuilder("Buzo,Short", 50_000, "hombre",
                "https://site/p1,https://site/p2", "", false, "gym");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> excluirCaptor = ArgumentCaptor.forClass(Set.class);
        verify(outfitService).armarPorCategorias(
                anyList(), anyList(), anyDouble(), anyString(), any(),
                excluirCaptor.capture(), anyBoolean(), anyList(), anyString());

        Set<String> captured = excluirCaptor.getValue();
        assertThat(captured).containsExactlyInAnyOrder("https://site/p1", "https://site/p2");
    }

    @Test
    void greedyParamTriggersModeFlag() {
        when(service.getLastResult()).thenReturn(aggregatedResult(List.of()));

        OutfitService.OutfitBuilderResult stub = new OutfitService.OutfitBuilderResult(
                List.of(), "hombre", 50_000, 0.0, false, List.of(), List.of(), null);
        when(outfitService.armarPorCategorias(anyList(), anyList(), anyDouble(), anyString(), any(), anySet(), anyBoolean(), anyList(), anyString()))
                .thenReturn(stub);

        controller.outfitsBuilder("Buzo", 50_000, "hombre", "", "", true, "gym");

        ArgumentCaptor<Boolean> greedyCaptor = ArgumentCaptor.forClass(Boolean.class);
        verify(outfitService).armarPorCategorias(
                anyList(), anyList(), anyDouble(), anyString(), any(),
                anySet(), greedyCaptor.capture(), anyList(), anyString());

        assertThat(greedyCaptor.getValue()).isTrue();
    }

    @Test
    void minimoBudgetNecesarioInNoFitResponse() {
        when(service.getLastResult()).thenReturn(aggregatedResult(List.of()));

        OutfitService.OutfitBuilderResult noFitWithMinimo = new OutfitService.OutfitBuilderResult(
                List.of(), "hombre", 5_000, 0.0,
                true, List.of(), List.of("Buzo"), 20_000.0);

        when(outfitService.armarPorCategorias(anyList(), anyList(), anyDouble(), anyString(), any(), anySet(), anyBoolean(), anyList(), anyString()))
                .thenReturn(noFitWithMinimo);

        ResponseEntity<?> resp = controller.outfitsBuilder("Buzo", 5_000, "hombre", "", "", false, "gym");

        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("status").asText()).isEqualTo("no-fit");
        assertThat(body.has("minimoBudgetNecesario")).isTrue();
        assertThat(body.path("minimoBudgetNecesario").asDouble()).isEqualTo(20_000.0);
    }

    @Test
    void minimoBudgetNecesarioAbsentOnSuccess() {
        when(service.getLastResult()).thenReturn(aggregatedResult(List.of()));

        OutfitService.SlotPick slot = new OutfitService.SlotPick(
                "Buzo", "Sitio", "Buzo Nike", 20_000,
                "https://test/buzo", "https://img/buzo.jpg", "Buzo", "Nike");
        OutfitService.OutfitBuilderResult success = new OutfitService.OutfitBuilderResult(
                List.of(slot), "hombre", 50_000, 20_000,
                false, List.of(), List.of(), null);

        when(outfitService.armarPorCategorias(anyList(), anyList(), anyDouble(), anyString(), any(), anySet(), anyBoolean(), anyList(), anyString()))
                .thenReturn(success);

        ResponseEntity<?> resp = controller.outfitsBuilder("Buzo", 50_000, "hombre", "", "", false, "gym");

        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("status").asText()).isEqualTo("ok");
        // minimoBudgetNecesario should be absent or null for success
        assertThat(body.has("minimoBudgetNecesario")).isFalse();
    }
}
