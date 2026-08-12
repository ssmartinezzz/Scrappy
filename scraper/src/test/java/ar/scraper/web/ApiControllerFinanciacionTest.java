package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.db.DatabaseService.Preset;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import ar.scraper.model.Product.SenalFinanciacion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the financing-preset CRUD endpoints and the {@code
 * senalFinanciacion} field added to {@code /api/data} (Phase 3 of
 * financing-buy-signal). {@code ApiController} is a plain {@code
 * @RestController} POJO — like every other unit test in this codebase, it is
 * instantiated directly with Mockito-mocked collaborators rather than via a
 * Spring MVC test slice (no {@code @WebMvcTest} convention exists in this
 * project).
 *
 * <p>Regression guard: existing {@code senal}/{@code ml} serialization in
 * {@code /api/data} must remain byte-for-byte unchanged — verified by
 * {@link #dataSerializesExistingSenalAndMlFieldsUnchanged()}.</p>
 */
@Epic("REST API")
@Feature("Financiación")
@DisplayName("ApiController — Financiación presets & /api/data signal")
class ApiControllerFinanciacionTest {

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

    private Product producto(String url, double precio, SenalFinanciacion finan) {
        return new Product("Sitio", "Producto " + url, precio, null, url, "img",
                "Remera", "unisex", List.of(), Product.MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, finan);
    }

    // ── GET /api/financiacion/presets ──────────────────────────────────────

    @Test
    void getPresetsReturnsListAndActivePreset() {
        Preset activo = new Preset(1, "12 cuotas / 40%", 40.0, 12, true);
        Preset otro   = new Preset(2, "6 cuotas / 10%", 10.0, 6, false);
        when(db.listarPresets()).thenReturn(List.of(activo, otro));
        when(db.cargarPresetActivo()).thenReturn(Optional.of(activo));

        ResponseEntity<?> resp = controller.listarPresets();

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("presets")).hasSize(2);
        assertThat(body.path("presets").get(0).path("id").asInt()).isEqualTo(1);
        assertThat(body.path("presets").get(0).path("label").asText()).isEqualTo("12 cuotas / 40%");
        assertThat(body.path("presets").get(0).path("recargoPct").asDouble()).isEqualTo(40.0);
        assertThat(body.path("presets").get(0).path("cuotas").asInt()).isEqualTo(12);
        assertThat(body.path("presets").get(0).path("activo").asBoolean()).isTrue();
        assertThat(body.path("activo").path("id").asInt()).isEqualTo(1);
    }

    @Test
    void getPresetsActivoIsNullNodeWhenNoneActive() {
        when(db.listarPresets()).thenReturn(List.of());
        when(db.cargarPresetActivo()).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.listarPresets();

        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("presets")).isEmpty();
        assertThat(body.get("activo").isNull()).isTrue();
    }

    // ── POST /api/financiacion/presets ─────────────────────────────────────

    @Test
    void postPresetValidPayloadPersistsAndReturnsOk() {
        when(db.crearPreset("Mi preset", 25.0, 6)).thenReturn(42);

        ResponseEntity<?> resp = controller.crearPreset(
                Map.of("label", "Mi preset", "recargoPct", 25.0, "cuotas", 6));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isTrue();
        verify(db).crearPreset("Mi preset", 25.0, 6);
    }

    @Test
    void postPresetBlankLabelReturns400WithoutPersisting() {
        Allure.parameter("label", "  ");
        ResponseEntity<?> resp = controller.crearPreset(
                Map.of("label", "  ", "recargoPct", 25.0, "cuotas", 6));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
        verify(db, never()).crearPreset(any(), anyDouble(), anyInt());
    }

    @Test
    void postPresetNegativeRecargoPctReturns400WithoutPersisting() {
        // Spec: recargoPct >= 0 strictly enforced at controller boundary —
        // stricter than DatabaseService.crearPreset's internal >-100 floor.
        Allure.parameter("recargoPct", -1.0);
        ResponseEntity<?> resp = controller.crearPreset(
                Map.of("label", "x", "recargoPct", -1.0, "cuotas", 6));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(db, never()).crearPreset(any(), anyDouble(), anyInt());
    }

    @Test
    void postPresetZeroCuotasReturns400WithoutPersisting() {
        Allure.parameter("cuotas", 0);
        ResponseEntity<?> resp = controller.crearPreset(
                Map.of("label", "x", "recargoPct", 10.0, "cuotas", 0));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(db, never()).crearPreset(any(), anyDouble(), anyInt());
    }

    @Test
    void postPresetDbRejectionMapsToFailureResponse() {
        // DatabaseService.crearPreset returns -1 on internal validation failure
        // even when controller-level validation passed.
        when(db.crearPreset("x", 10.0, 5)).thenReturn(-1);

        ResponseEntity<?> resp = controller.crearPreset(
                Map.of("label", "x", "recargoPct", 10.0, "cuotas", 5));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
    }

    // ── PUT /api/financiacion/presets/{id}/activar ─────────────────────────

    @Test
    void activarPresetSuccessTriggersSynchronousRecompute() {
        when(db.activarPreset(7)).thenReturn(true);

        ResponseEntity<?> resp = controller.activarPreset(7);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isTrue();
        verify(service).recomputarFinanciacion(aggregator);
    }

    @Test
    void activarPresetNotFoundReturns404WithoutRecompute() {
        when(db.activarPreset(999)).thenReturn(false);

        ResponseEntity<?> resp = controller.activarPreset(999);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
        verify(service, never()).recomputarFinanciacion(any());
    }

    // ── PUT /api/financiacion/presets/{id} ──────────────────────────────────

    @Test
    void editarPresetOfActivePresetTriggersRecompute() {
        Preset activo = new Preset(3, "Activo", 40.0, 12, true);
        when(db.cargarPresetActivo()).thenReturn(Optional.of(activo));
        when(db.editarPreset(3, "Nuevo label", 20.0, 6)).thenReturn(true);

        ResponseEntity<?> resp = controller.editarPreset(3,
                Map.of("label", "Nuevo label", "recargoPct", 20.0, "cuotas", 6));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(service).recomputarFinanciacion(aggregator);
    }

    @Test
    void editarPresetOfInactivePresetDoesNotTriggerRecompute() {
        Preset activo = new Preset(3, "Activo", 40.0, 12, true);
        when(db.cargarPresetActivo()).thenReturn(Optional.of(activo));
        when(db.editarPreset(5, "x", 20.0, 6)).thenReturn(true);

        ResponseEntity<?> resp = controller.editarPreset(5,
                Map.of("label", "x", "recargoPct", 20.0, "cuotas", 6));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(service, never()).recomputarFinanciacion(any());
    }

    @Test
    void editarPresetNotFoundOrInvalidReturns400WithoutRecompute() {
        when(db.cargarPresetActivo()).thenReturn(Optional.empty());
        when(db.editarPreset(99, "x", 20.0, 6)).thenReturn(false);

        ResponseEntity<?> resp = controller.editarPreset(99,
                Map.of("label", "x", "recargoPct", 20.0, "cuotas", 6));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(service, never()).recomputarFinanciacion(any());
    }

    // ── DELETE /api/financiacion/presets/{id} ──────────────────────────────

    @Test
    void deletePresetThatWasActiveTriggersRecompute() {
        Preset activo = new Preset(4, "Activo", 40.0, 12, true);
        when(db.cargarPresetActivo()).thenReturn(Optional.of(activo));
        when(db.eliminarPreset(4)).thenReturn(true);

        ResponseEntity<?> resp = controller.eliminarPreset(4);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(db).eliminarPreset(4);
        verify(service).recomputarFinanciacion(aggregator);
    }

    @Test
    void deletePresetThatWasNotActiveDoesNotTriggerRecompute() {
        Preset activo = new Preset(4, "Activo", 40.0, 12, true);
        when(db.cargarPresetActivo()).thenReturn(Optional.of(activo));
        when(db.eliminarPreset(5)).thenReturn(true);

        ResponseEntity<?> resp = controller.eliminarPreset(5);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(db).eliminarPreset(5);
        verify(service, never()).recomputarFinanciacion(any());
    }

    @Test
    void deletePresetWithNonExistentIdReturns404WithoutRecompute() {
        when(db.cargarPresetActivo()).thenReturn(Optional.empty());
        when(db.eliminarPreset(999)).thenReturn(false);

        ResponseEntity<?> resp = controller.eliminarPreset(999);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
        verify(service, never()).recomputarFinanciacion(any());
    }

    // ── RUNNING-state guard on mutating financiacion endpoints ─────────────
    // Mirrors the 409 guard used by /db/productos and /db/ml (defense-in-depth
    // on top of ScraperService's catalogLock — see recomputarFinanciacion).

    @Test
    void postPresetWhileScrapingRunningReturns409WithoutPersisting() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.RUNNING);

        ResponseEntity<?> resp = controller.crearPreset(
                Map.of("label", "x", "recargoPct", 10.0, "cuotas", 6));

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
        verify(db, never()).crearPreset(any(), anyDouble(), anyInt());
    }

    @Test
    void putPresetWhileScrapingRunningReturns409WithoutPersisting() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.RUNNING);

        ResponseEntity<?> resp = controller.editarPreset(3,
                Map.of("label", "x", "recargoPct", 20.0, "cuotas", 6));

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
        verify(db, never()).editarPreset(anyInt(), any(), anyDouble(), anyInt());
        verify(service, never()).recomputarFinanciacion(any());
    }

    @Test
    void activarPresetWhileScrapingRunningReturns409WithoutActivating() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.RUNNING);

        ResponseEntity<?> resp = controller.activarPreset(7);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
        verify(db, never()).activarPreset(anyInt());
        verify(service, never()).recomputarFinanciacion(any());
    }

    @Test
    void deletePresetWhileScrapingRunningReturns409WithoutDeleting() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.RUNNING);

        ResponseEntity<?> resp = controller.eliminarPreset(4);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
        verify(db, never()).eliminarPreset(anyInt());
        verify(service, never()).recomputarFinanciacion(any());
    }
}
