package ar.scraper.web;

import ar.scraper.aggregator.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.ml.PythonRunner.TrainingStatus;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

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
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.path("training").get("running").asBoolean()).isTrue();
        assertThat(body.path("training").get("pct").asInt()).isEqualTo(45);
        assertThat(body.path("training").get("phase").asText()).isEqualTo("training");
    }

    // ── GET /api/ml/resultado ────────────────────────────────────────────

    @Test
    void mlResultadoReturnsDoneTrueWhenTrainingFinished() {
        when(pythonRunner.getTrainingStatus())
                .thenReturn(new TrainingStatus(false, "done", 100, "Completado", "2026-01-01T10:00"));

        var resp = controller.mlResultado();
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("done").asBoolean()).isTrue();
        assertThat(body.get("running").asBoolean()).isFalse();
        assertThat(body.get("phase").asText()).isEqualTo("done");
    }

    @Test
    void mlResultadoReturnsDoneFalseWhenIdle() {
        when(pythonRunner.getTrainingStatus()).thenReturn(TrainingStatus.idle());

        var resp = controller.mlResultado();
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("done").asBoolean()).isFalse();
        assertThat(body.get("phase").asText()).isEqualTo("idle");
    }

    // ── POST /api/ml/entrenar ────────────────────────────────────────────

    @Test
    void mlEntrenarReturns400WhenTrainingAlreadyRunning() {
        when(pythonRunner.isTrainingRunning()).thenReturn(true);

        var resp = controller.mlEntrenar(false, 8);
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(body.get("error").asText()).contains("curso");
        verify(pythonRunner, never()).entrenarEnBackground(any(), anyBoolean(), anyBoolean(), anyInt());
    }

    @Test
    void mlEntrenarReturnsStartedWhenIdle() {
        when(pythonRunner.isTrainingRunning()).thenReturn(false);

        var resp = controller.mlEntrenar(false, 8);
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("status").asText()).isEqualTo("started");
        verify(pythonRunner).entrenarEnBackground(any(), eq(true), eq(false), eq(8));
    }

    // ── POST /api/ml/aplicar ─────────────────────────────────────────────

    @Test
    void mlAplicarReturns400WhenNoDataLoaded() {
        when(service.getLastResult()).thenReturn(null);

        var resp = controller.mlAplicar();
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(body.get("error").asText()).contains("scraping");
    }

    @Test
    void mlAplicarReturnsStartedWhenDataExists() {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        var result = new AggregatedResult(List.of(), Map.of(), Map.of(), facets, 0, 0);
        when(service.getLastResult()).thenReturn(result);

        var resp = controller.mlAplicar();
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

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
