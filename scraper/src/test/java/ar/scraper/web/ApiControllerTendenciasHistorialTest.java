package ar.scraper.web;

import ar.scraper.aggregator.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.db.DatabaseService.HistorialEntry;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApiControllerTendenciasHistorialTest {

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

    // ── GET /api/tendencias ──────────────────────────────────────────────

    @Test
    void tendenciasReturns204WhenNoLastResult() {
        when(service.getLastResult()).thenReturn(null);

        var resp = controller.tendencias();

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void tendenciasReturns503WhenMlOutputIsNull() {
        when(service.getLastResult()).thenReturn(mockResult(List.of()));
        when(aggregator.getLastMlOutput()).thenReturn(null);

        var resp = controller.tendencias();
        JsonNode body = resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        assertThat(body.get("error").asText()).isEqualTo("ml_failed");
    }

    @Test
    void tendenciasReturns204WhenMlOutputHasNoUsableData() throws Exception {
        when(service.getLastResult()).thenReturn(mockResult(List.of()));
        ObjectNode empty = new ObjectMapper().createObjectNode();
        empty.putObject("scores");  // empty scores object
        empty.putObject("tendencias");
        when(aggregator.getLastMlOutput()).thenReturn(empty);

        var resp = controller.tendencias();

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void tendenciasReturns200WithTendenciasWhenMlOutputIsValid() throws Exception {
        when(service.getLastResult()).thenReturn(mockResult(List.of()));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode ml = mapper.createObjectNode();
        ObjectNode scores = ml.putObject("scores");
        scores.put("https://a.com/1", 50);
        ObjectNode tendencias = ml.putObject("tendencias");
        tendencias.put("topCategoria", "Zapatillas");
        when(aggregator.getLastMlOutput()).thenReturn(ml);
        when(db.cargarCategoriaStats()).thenReturn(Map.of());

        var resp = controller.tendencias();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("topCategoria").asText()).isEqualTo("Zapatillas");
    }

    // ── GET /api/historial ───────────────────────────────────────────────

    @Test
    void historialReturns204WhenNoHistoryFound() {
        when(db.cargarHistorial("https://sitio.com/p/1")).thenReturn(List.of());

        var resp = controller.historial("https://sitio.com/p/1");

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void historialReturnsPuntosAndStatsWhenMultiplePricePoints() throws Exception {
        var entries = new ArrayList<Map<String, Object>>();
        entries.add(punto("2025-01-01", 10000.0));
        entries.add(punto("2025-02-01", 12000.0));
        entries.add(punto("2025-03-01", 9000.0));
        when(db.cargarHistorial("https://a.com/1")).thenReturn(entries);

        var resp = controller.historial("https://a.com/1");
        JsonNode body = new ObjectMapper().readTree(
                new ObjectMapper().writeValueAsString(resp.getBody()));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("puntos").size()).isEqualTo(3);
        assertThat(body.get("min").asDouble()).isEqualTo(9000.0);
        assertThat(body.get("max").asDouble()).isEqualTo(12000.0);
    }

    @Test
    void historialReturnsSinglePuntoWithoutStatsWhenOnlyOnePoint() throws Exception {
        var entries = new ArrayList<Map<String, Object>>();
        entries.add(punto("2025-01-01", 10000.0));
        when(db.cargarHistorial("https://a.com/1")).thenReturn(entries);

        var resp = controller.historial("https://a.com/1");
        JsonNode body = new ObjectMapper().readTree(
                new ObjectMapper().writeValueAsString(resp.getBody()));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("puntos").size()).isEqualTo(1);
        assertThat(body.has("min")).isFalse();
    }

    // ── GET /api/grupos ──────────────────────────────────────────────────

    @Test
    void gruposReturns204WhenNoLastResult() {
        when(service.getLastResult()).thenReturn(null);

        var resp = controller.grupos(null, null, null, null, 2, 0, 20);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void gruposReturns200WithPaginatedGroupsWhenResultExists() throws Exception {
        when(service.getLastResult()).thenReturn(mockResult(List.of()));
        when(grouping.agrupar(any(), anyBoolean())).thenReturn(List.of());

        var resp = controller.grupos(null, null, null, null, 2, 0, 20);
        JsonNode body = new ObjectMapper().readTree(
                new ObjectMapper().writeValueAsString(resp.getBody()));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("total").asInt()).isEqualTo(0);
        assertThat(body.get("grupos").isArray()).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static Map<String, Object> punto(String fecha, double precio) {
        Map<String, Object> m = new HashMap<>();
        m.put("fecha", fecha);
        m.put("precio", precio);
        return m;
    }

    private AggregatedResult mockResult(List<Product> products) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(products, Map.of(), Map.of(), facets, 0, 0);
    }
}
