package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.db.DatabaseService.HistorialEntry;
import ar.scraper.ml.PythonRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApiControllerInflacionRecomendacionTest {

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

    // ── GET /api/inflacion ───────────────────────────────────────────────

    @Test
    void inflacionReturnsAllExpectedFields() {
        when(inflacionService.getInflacionMensual()).thenReturn(4.2);
        when(inflacionService.getInflacionInteranual()).thenReturn(118.0);
        when(inflacionService.getInflacion3m()).thenReturn(13.5);
        when(inflacionService.getUltimaActualizacion()).thenReturn("2025-01-01");
        when(inflacionService.getHistorial()).thenReturn(List.of(
                new InflacionService.DatoIPC("2024-12-01", 150.0, 4.0),
                new InflacionService.DatoIPC("2025-01-01", 156.3, 4.2)));

        var resp = controller.inflacion();
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("mensual").asDouble()).isEqualTo(4.2);
        assertThat(body.get("interanual").asDouble()).isEqualTo(118.0);
        assertThat(body.get("acumulada3m").asDouble()).isEqualTo(13.5);
        assertThat(body.get("actualizado").asText()).isEqualTo("2025-01-01");
        assertThat(body.get("historial").isArray()).isTrue();
        assertThat(body.get("historial").size()).isEqualTo(2);
    }

    @Test
    void inflacionHistorialLimitedTo13Entries() {
        var manyPoints = new java.util.ArrayList<InflacionService.DatoIPC>();
        for (int i = 0; i < 20; i++) {
            manyPoints.add(new InflacionService.DatoIPC("2024-" + (i + 1) + "-01", 100.0 + i, 1.0));
        }
        when(inflacionService.getHistorial()).thenReturn(manyPoints);

        var resp = controller.inflacion();
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("historial").size()).isEqualTo(13);
    }

    // ── GET /api/recomendacion ───────────────────────────────────────────

    @Test
    void recomendacionReturnsSinDatosWhenNoHistory() {
        when(db.getHistorialPrecios("https://a.com/1")).thenReturn(List.of());

        var resp = controller.recomendacion("https://a.com/1");
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("senal").asText()).isEqualTo("sin_datos");
    }

    @Test
    void recomendacionReturnsComprarAhoraCuandoPrecioEnMinimo() {
        // Price at historical minimum (pctDelMin <= 10%)
        var historial = new ArrayList<>(List.of(
                new HistorialEntry("2024-11-01", 20000.0),
                new HistorialEntry("2024-12-01", 25000.0),
                new HistorialEntry("2025-01-01", 20100.0)));  // essentially at minimum

        when(db.getHistorialPrecios("https://a.com/1")).thenReturn(historial);
        when(inflacionService.ajustarPorInflacion(anyDouble(), anyInt())).thenReturn(20000.0);
        when(inflacionService.getInflacionMensual()).thenReturn(4.0);
        when(inflacionService.getInflacionInteranual()).thenReturn(50.0);

        var resp = controller.recomendacion("https://a.com/1");
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("senal").asText()).isEqualTo("comprar_ahora");
        assertThat(body.get("scoreCompra").asInt()).isEqualTo(95);
    }

    @Test
    void recomendacionReturnsPrecioNormalForMidRangePrice() {
        var historial = new ArrayList<>(List.of(
                new HistorialEntry("2024-11-01", 10000.0),
                new HistorialEntry("2024-12-01", 15000.0),
                new HistorialEntry("2025-01-01", 12500.0)));  // 50% of range — normal

        when(db.getHistorialPrecios("https://a.com/1")).thenReturn(historial);
        when(inflacionService.ajustarPorInflacion(anyDouble(), anyInt())).thenReturn(10000.0);
        when(inflacionService.getInflacionMensual()).thenReturn(4.0);
        when(inflacionService.getInflacionInteranual()).thenReturn(50.0);

        var resp = controller.recomendacion("https://a.com/1");
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("senal").asText()).isEqualTo("precio_normal");
        assertThat(body.get("scoreCompra").asInt()).isEqualTo(50);
        assertThat(body.has("puntosHistorial")).isTrue();
    }

    @Test
    void recomendacionIncludesInflacionFieldsInResponse() {
        var historial = new ArrayList<>(List.of(new HistorialEntry("2025-01-01", 10000.0)));
        when(db.getHistorialPrecios("https://a.com/1")).thenReturn(historial);
        when(inflacionService.getInflacionMensual()).thenReturn(4.2);
        when(inflacionService.getInflacionInteranual()).thenReturn(100.0);

        var resp = controller.recomendacion("https://a.com/1");
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("inflacionMensual").asDouble()).isEqualTo(4.2);
        assertThat(body.get("inflacionInteranual").asDouble()).isEqualTo(100.0);
    }
}
