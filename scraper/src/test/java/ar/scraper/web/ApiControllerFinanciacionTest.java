package ar.scraper.web;

import ar.scraper.aggregator.GroupingService;
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
import org.junit.jupiter.api.BeforeEach;
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

    private Product producto(String url, double precio, SenalFinanciacion finan) {
        return new Product("Sitio", "Producto " + url, precio, null, url, "img",
                "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "Marca", "indumentaria",
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
        ResponseEntity<?> resp = controller.crearPreset(
                Map.of("label", "x", "recargoPct", -1.0, "cuotas", 6));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(db, never()).crearPreset(any(), anyDouble(), anyInt());
    }

    @Test
    void postPresetZeroCuotasReturns400WithoutPersisting() {
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

    // ── GET /api/data — senalFinanciacion field ────────────────────────────

    @Test
    void dataSerializesSenalFinanciacionPerProduct() {
        SenalFinanciacion finan = new SenalFinanciacion("conviene_cuotas", 8.0, 92000, 7666, 12, 40);
        Product p = producto("https://site.com/a", 100000, finan);
        AggregatedResult result = new AggregatedResult(
                List.of(p), Map.of("Sitio", 1), Map.of(),
                ResultAggregator.calcularFacets(List.of(p)), 100000, 100000);
        when(service.getLastResult()).thenReturn(result);
        when(config.getMoneda()).thenReturn("ARS");
        Preset activo = new Preset(1, "12 cuotas / 40%", 40.0, 12, true);
        when(db.cargarPresetActivo()).thenReturn(Optional.of(activo));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null);

        JsonNode body = (JsonNode) resp.getBody();
        JsonNode prod0 = body.path("productos").get(0);
        JsonNode finanNode = prod0.path("senalFinanciacion");
        assertThat(finanNode.path("senal").asText()).isEqualTo("conviene_cuotas");
        assertThat(finanNode.path("ahorroReal").asDouble()).isEqualTo(8.0);
        assertThat(finanNode.path("vp").asDouble()).isEqualTo(92000);
        assertThat(finanNode.path("presetLabel").asText()).isEqualTo("12 cuotas / 40%");
    }

    @Test
    void dataLooksUpActivePresetOnceRegardlessOfProductCount() {
        SenalFinanciacion finan = new SenalFinanciacion("indistinto", 1.0, 5050, 420, 12, 40);
        Product p1 = producto("https://site.com/a", 5000, finan);
        Product p2 = producto("https://site.com/b", 6000, finan);
        AggregatedResult result = new AggregatedResult(
                List.of(p1, p2), Map.of("Sitio", 2), Map.of(),
                ResultAggregator.calcularFacets(List.of(p1, p2)), 5000, 6000);
        when(service.getLastResult()).thenReturn(result);
        when(config.getMoneda()).thenReturn("ARS");
        Preset activo = new Preset(1, "Preset", 40.0, 12, true);
        when(db.cargarPresetActivo()).thenReturn(Optional.of(activo));

        controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null);

        verify(db, times(1)).cargarPresetActivo();
    }

    @Test
    void dataSerializesSinPresetActivoLabelAsEmptyWhenNoneActive() {
        SenalFinanciacion finan = new SenalFinanciacion("sin_preset_activo", 0, 0, 0, 0, 0);
        Product p = producto("https://site.com/a", 1000, finan);
        AggregatedResult result = new AggregatedResult(
                List.of(p), Map.of("Sitio", 1), Map.of(),
                ResultAggregator.calcularFacets(List.of(p)), 1000, 1000);
        when(service.getLastResult()).thenReturn(result);
        when(config.getMoneda()).thenReturn("ARS");
        when(db.cargarPresetActivo()).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null);

        JsonNode body = (JsonNode) resp.getBody();
        JsonNode finanNode = body.path("productos").get(0).path("senalFinanciacion");
        assertThat(finanNode.path("senal").asText()).isEqualTo("sin_preset_activo");
        assertThat(finanNode.path("presetLabel").asText()).isEqualTo("");
    }

    // ── Regression guard: existing senal/ml fields unchanged ───────────────

    @Test
    void dataSerializesExistingSenalAndMlFieldsUnchanged() {
        Product.SenalCompra senal = new Product.SenalCompra("comprar_ahora", 95);
        Product.MlScore ml = new Product.MlScore(80, "oferta_real", true, "bajando", 12, 0.5, "premium");
        Product p = new Product("Sitio", "Producto", 1000, null, "https://site.com/a", "img",
                "Remeras", "unisex", List.of(), ml, "Marca", "indumentaria",
                false, false, senal, SenalFinanciacion.EMPTY);
        AggregatedResult result = new AggregatedResult(
                List.of(p), Map.of("Sitio", 1), Map.of(),
                ResultAggregator.calcularFacets(List.of(p)), 1000, 1000);
        when(service.getLastResult()).thenReturn(result);
        when(config.getMoneda()).thenReturn("ARS");
        when(db.cargarPresetActivo()).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null);

        JsonNode body = (JsonNode) resp.getBody();
        JsonNode prod0 = body.path("productos").get(0);

        // senal — byte-for-byte unchanged shape
        assertThat(prod0.path("senal").path("senal").asText()).isEqualTo("comprar_ahora");
        assertThat(prod0.path("senal").path("scoreCompra").asInt()).isEqualTo(95);

        // ml — byte-for-byte unchanged shape
        assertThat(prod0.path("ml").path("badge").asText()).isEqualTo("oferta_real");
        assertThat(prod0.path("ml").path("scoreP").asInt()).isEqualTo(80);
        assertThat(prod0.path("ml").path("ofertaReal").asBoolean()).isTrue();
        assertThat(prod0.path("ml").path("tendencia").asText()).isEqualTo("bajando");
        assertThat(prod0.path("ml").path("pctil").asInt()).isEqualTo(12);
        assertThat(prod0.path("ml").path("zScore").asDouble()).isEqualTo(0.5);
        assertThat(prod0.path("ml").path("segment").asText()).isEqualTo("premium");

        // senalFinanciacion is additive, sibling — never merged into senal/ml
        assertThat(prod0.has("senalFinanciacion")).isTrue();
        assertThat(prod0.path("senalFinanciacion").path("senal").asText())
                .isNotEqualTo(prod0.path("senal").path("senal").asText());
    }

    // ── /api/data — cantidadUnidades / esPack / precioUnitario (PR2) ───────

    @Test
    void dataSerializesCantidadUnidadesEsPackAndPrecioUnitarioForPack() {
        Product pack = new Product(
                "Sitio", "Pack x3 Remeras", 15000, null, "https://site.com/pack", "img",
                "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 3);
        AggregatedResult result = new AggregatedResult(
                List.of(pack), Map.of("Sitio", 1), Map.of(),
                ResultAggregator.calcularFacets(List.of(pack)), 15000, 15000);
        when(service.getLastResult()).thenReturn(result);
        when(config.getMoneda()).thenReturn("ARS");
        when(db.cargarPresetActivo()).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null);

        JsonNode prod0 = ((JsonNode) resp.getBody()).path("productos").get(0);
        assertThat(prod0.path("cantidadUnidades").asInt()).isEqualTo(3);
        assertThat(prod0.path("esPack").asBoolean()).isTrue();
        assertThat(prod0.path("precioUnitario").asDouble()).isEqualTo(5000.0);
    }

    @Test
    void dataSerializesCantidadUnidadesAsOneAndEsPackFalseForSingleUnit() {
        Product single = producto("https://site.com/single", 5000, SenalFinanciacion.EMPTY);
        AggregatedResult result = new AggregatedResult(
                List.of(single), Map.of("Sitio", 1), Map.of(),
                ResultAggregator.calcularFacets(List.of(single)), 5000, 5000);
        when(service.getLastResult()).thenReturn(result);
        when(config.getMoneda()).thenReturn("ARS");
        when(db.cargarPresetActivo()).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null);

        JsonNode prod0 = ((JsonNode) resp.getBody()).path("productos").get(0);
        assertThat(prod0.path("cantidadUnidades").asInt()).isEqualTo(1);
        assertThat(prod0.path("esPack").asBoolean()).isFalse();
        assertThat(prod0.path("precioUnitario").asDouble()).isEqualTo(5000.0);
    }

    @Test
    void dataFacetsExposePackCountOverFullUnfilteredDataset() {
        Product pack = new Product(
                "Sitio", "Pack x2", 10000, null, "https://site.com/pack2", "img",
                "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 2);
        Product single = producto("https://site.com/single2", 5000, SenalFinanciacion.EMPTY);
        AggregatedResult result = new AggregatedResult(
                List.of(pack, single), Map.of("Sitio", 2), Map.of(),
                ResultAggregator.calcularFacets(List.of(pack, single)), 5000, 10000);
        when(service.getLastResult()).thenReturn(result);
        when(config.getMoneda()).thenReturn("ARS");
        when(db.cargarPresetActivo()).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null);

        JsonNode facetsNode = ((JsonNode) resp.getBody()).path("meta").path("facets");
        assertThat(facetsNode.path("packCount").asInt()).isEqualTo(1);
    }

    // ── /api/data?pack=true — server-side pack filter (PR3) ─────────────────
    // Mirrors the existing gymrat Boolean filter pattern: only applied when
    // explicitly true; absent/false/null param does not filter the dataset.

    @Test
    void dataPackTrueFiltersToOnlyPackProducts() {
        Product pack = new Product(
                "Sitio", "Pack x3 Remeras", 15000, null, "https://site.com/packf1", "img",
                "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 3);
        Product single = producto("https://site.com/singlef1", 5000, SenalFinanciacion.EMPTY);
        AggregatedResult result = new AggregatedResult(
                List.of(pack, single), Map.of("Sitio", 2), Map.of(),
                ResultAggregator.calcularFacets(List.of(pack, single)), 5000, 15000);
        when(service.getLastResult()).thenReturn(result);
        when(config.getMoneda()).thenReturn("ARS");
        when(db.cargarPresetActivo()).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", true, null, null, null);

        JsonNode prods = ((JsonNode) resp.getBody()).path("productos");
        assertThat(prods).hasSize(1);
        assertThat(prods.get(0).path("url").asText()).isEqualTo("https://site.com/packf1");
        assertThat(prods.get(0).path("esPack").asBoolean()).isTrue();
    }

    @Test
    void dataPackFalseOrNullDoesNotFilterDataset() {
        Product pack = new Product(
                "Sitio", "Pack x3 Remeras", 15000, null, "https://site.com/packf2", "img",
                "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 3);
        Product single = producto("https://site.com/singlef2", 5000, SenalFinanciacion.EMPTY);
        AggregatedResult result = new AggregatedResult(
                List.of(pack, single), Map.of("Sitio", 2), Map.of(),
                ResultAggregator.calcularFacets(List.of(pack, single)), 5000, 15000);
        when(service.getLastResult()).thenReturn(result);
        when(config.getMoneda()).thenReturn("ARS");
        when(db.cargarPresetActivo()).thenReturn(Optional.empty());

        ResponseEntity<?> respNull = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null);
        ResponseEntity<?> respFalse = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", false, null, null, null);

        assertThat(((JsonNode) respNull.getBody()).path("productos")).hasSize(2);
        assertThat(((JsonNode) respFalse.getBody()).path("productos")).hasSize(2);
    }

    // ── /api/facets — packCount ──────────────────────────────────────────────

    @Test
    void facetsEndpointExposesPackCount() {
        Product pack = new Product(
                "Sitio", "Pack x2", 10000, null, "https://site.com/pack3", "img",
                "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 2);
        Product single = producto("https://site.com/single3", 5000, SenalFinanciacion.EMPTY);
        AggregatedResult result = new AggregatedResult(
                List.of(pack, single), Map.of("Sitio", 2), Map.of(),
                ResultAggregator.calcularFacets(List.of(pack, single)), 5000, 10000);
        when(service.getLastResult()).thenReturn(result);

        ResponseEntity<?> resp = controller.facets();

        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("packCount").asInt()).isEqualTo(1);
    }
}
