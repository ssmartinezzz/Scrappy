package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.ml.PythonRunner.TrainingStatus;
import ar.scraper.model.Product;
import ar.scraper.testsupport.AllureSteps;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Epic("REST API")
@Feature("Tendencias / ML Ops")
@Story("ML ops")
@DisplayName("ApiController — ML ops endpoints")
class ApiControllerMlOpsTest {

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

    // ── GET /api/ml/estado ───────────────────────────────────────────────

    @Test
    void mlEstadoReturnsTrainingStatusFields() {
        when(pythonRunner.getTrainingStatus())
                .thenReturn(new TrainingStatus(false, "idle", 0, "", null));

        var resp = controller.mlEstado();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.has("hasTextModel")).isTrue();
        assertThat(body.has("hasImageModel")).isTrue();
        assertThat(body.path("training").get("running").asBoolean()).isFalse();
        assertThat(body.path("training").get("phase").asText()).isEqualTo("idle");
    }

    @Test
    void mlEstadoReturnsRunningWhenTrainingInProgress() {
        when(pythonRunner.getTrainingStatus())
                .thenReturn(new TrainingStatus(true, "training", 45, "Epoch 4/8", "2026-01-01T10:00"));

        var resp = controller.mlEstado();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.path("training").get("running").asBoolean()).isTrue();
        assertThat(body.path("training").get("pct").asInt()).isEqualTo(45);
        assertThat(body.path("training").get("phase").asText()).isEqualTo("training");
    }

    // T6.3/T6.4: /api/ml/estado additionally reports embeddingsCount + coverage
    // against the current catalog, and the "training"/"embedding" macro phase
    // values from T5.4's sequencing entrypoint pass through unchanged — all
    // additive, existing fields (hasTextModel/hasImageModel/training.*) unaffected.
    @Test
    void mlEstadoReturnsEmbeddingsCountAndCoverage() {
        when(pythonRunner.getTrainingStatus())
                .thenReturn(new TrainingStatus(false, "idle", 0, "", null));
        when(db.contarEmbeddings()).thenReturn(75L);
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        List<Product> productos = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            productos.add(new Product("Sitio", "Producto " + i, 1000.0, null,
                    "https://site.com/" + i, "", "Remeras", "unisex", List.of()));
        }
        var result = new AggregatedResult(productos, Map.of(), Map.of(), facets, 0, 0);
        when(service.getLastResult()).thenReturn(result);

        var resp = controller.mlEstado();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        // Existing fields remain intact
        assertThat(body.has("hasTextModel")).isTrue();
        assertThat(body.path("training").get("phase").asText()).isEqualTo("idle");
        // New additive fields
        assertThat(body.get("embeddingsCount").asLong()).isEqualTo(75L);
        assertThat(body.get("totalProductos").asInt()).isEqualTo(100);
        assertThat(body.get("coveragePct").asDouble()).isEqualTo(75.0);
    }

    @Test
    void mlEstadoReturnsZeroCoverageWhenNoDataLoaded() {
        when(pythonRunner.getTrainingStatus())
                .thenReturn(TrainingStatus.idle());
        when(db.contarEmbeddings()).thenReturn(0L);
        when(service.getLastResult()).thenReturn(null);

        var resp = controller.mlEstado();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.get("embeddingsCount").asLong()).isEqualTo(0L);
        assertThat(body.get("totalProductos").asInt()).isEqualTo(0);
        assertThat(body.get("coveragePct").asDouble()).isEqualTo(0.0);
    }

    @Test
    void mlEstadoReportsEmbeddingMacroPhaseFromSequencingEntrypoint() {
        when(pythonRunner.getTrainingStatus())
                .thenReturn(new TrainingStatus(true, "embedding", 70, "35/50", "2026-01-01T10:00"));
        when(db.contarEmbeddings()).thenReturn(35L);
        when(service.getLastResult()).thenReturn(null);

        var resp = controller.mlEstado();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.path("training").get("phase").asText()).isEqualTo("embedding");
        assertThat(body.path("training").get("running").asBoolean()).isTrue();
    }

    // ── GET /api/ml/resultado ────────────────────────────────────────────

    @Test
    void mlResultadoReturnsDoneTrueWhenTrainingFinished() {
        when(pythonRunner.getTrainingStatus())
                .thenReturn(new TrainingStatus(false, "done", 100, "Completado", "2026-01-01T10:00"));

        var resp = controller.mlResultado();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.get("done").asBoolean()).isTrue();
        assertThat(body.get("running").asBoolean()).isFalse();
        assertThat(body.get("phase").asText()).isEqualTo("done");
    }

    @Test
    void mlResultadoReturnsDoneFalseWhenIdle() {
        when(pythonRunner.getTrainingStatus()).thenReturn(TrainingStatus.idle());

        var resp = controller.mlResultado();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.get("done").asBoolean()).isFalse();
        assertThat(body.get("phase").asText()).isEqualTo("idle");
    }

    // ── POST /api/ml/entrenar ────────────────────────────────────────────

    @Test
    void mlEntrenarReturns400WhenTrainingAlreadyRunning() {
        when(pythonRunner.isTrainingRunning()).thenReturn(true);

        var resp = controller.mlEntrenar(false, 8);
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(body.get("error").asText()).contains("curso");
        verify(pythonRunner, never())
                .construirIndiceVisualEnBackground(any(), anyBoolean(), anyBoolean(), anyInt(), anyBoolean());
    }

    // T6.1/T6.2: /api/ml/entrenar drives the T5.4 sequencing entrypoint
    // (construirIndiceVisualEnBackground), which runs text re-training FIRST
    // then the embeddings backfill on ONE background thread — never the
    // standalone entrenarEnBackground path (still LIVE for post-scrape
    // auto-training via ResultAggregator, just not this endpoint's path).
    @Test
    void mlEntrenarReturnsStartedWhenIdle() {
        when(pythonRunner.isTrainingRunning()).thenReturn(false);
        when(pythonRunner.construirIndiceVisualEnBackground(any(), anyBoolean(), anyBoolean(), anyInt(), anyBoolean()))
                .thenReturn(true);

        var resp = controller.mlEntrenar(false, 8);
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("status").asText()).isEqualTo("started");
        verify(pythonRunner).construirIndiceVisualEnBackground(any(), eq(true), eq(false), eq(8), eq(true));
        verify(pythonRunner, never()).entrenarEnBackground(any(), anyBoolean(), anyBoolean(), anyInt());
    }

    // RESI-002 ≡ RELY-001 (4R PR6 follow-up): two near-simultaneous POSTs can
    // both pass the isTrainingRunning() pre-check; the atomic CAS inside
    // construirIndiceVisualEnBackground picks one winner. The loser's request
    // is NOT started — it must get a 409 Conflict, never a false 200 "started".
    @Test
    void mlEntrenarReturns409WhenLosingTheStartRace() {
        when(pythonRunner.isTrainingRunning()).thenReturn(false);
        when(pythonRunner.construirIndiceVisualEnBackground(any(), anyBoolean(), anyBoolean(), anyInt(), anyBoolean()))
                .thenReturn(false);

        var resp = controller.mlEntrenar(false, 8);
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(body.get("error").asText()).contains("curso");
    }

    @Test
    void mlEntrenarDoesNotBlockOnConstruirIndiceVisualEnBackgroundCall() {
        when(pythonRunner.isTrainingRunning()).thenReturn(false);
        // Simulates the real contract: construirIndiceVisualEnBackground itself
        // dispatches to a background thread and returns immediately (true =
        // reservation won) — the controller must never await any callback/latch,
        // just delegate and respond "started" synchronously.
        when(pythonRunner.construirIndiceVisualEnBackground(any(), anyBoolean(), anyBoolean(), anyInt(), anyBoolean()))
                .thenReturn(true);

        long start = System.nanoTime();
        var resp = controller.mlEntrenar(true, 8);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(elapsedMs).isLessThan(500);
        verify(pythonRunner).construirIndiceVisualEnBackground(any(), eq(true), eq(true), eq(8), eq(true));
    }

    // ── POST /api/ml/aplicar ─────────────────────────────────────────────

    @Test
    void mlAplicarReturns400WhenNoDataLoaded() {
        when(service.getLastResult()).thenReturn(null);

        var resp = controller.mlAplicar();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(body.get("error").asText()).contains("scraping");
    }

    @Test
    void mlAplicarReturnsStartedWhenDataExists() {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        var result = new AggregatedResult(List.of(), Map.of(), Map.of(), facets, 0, 0);
        when(service.getLastResult()).thenReturn(result);

        var resp = controller.mlAplicar();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("status").asText()).isEqualTo("started");
    }

    // ── POST /api/ml/renormalizar ────────────────────────────────────────

    @Test
    void mlRenormalizarDelegatesToAggregatorAndReturnsResult() {
        when(aggregator.renormalizarCatalogo()).thenReturn(Map.of("refinados", 5));

        var resp = controller.mlRenormalizar();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(aggregator).renormalizarCatalogo();
    }
}
