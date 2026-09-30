package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.web.support.Wire;


import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.Facets;
import ar.scraper.catalog.HistorialPort;
import ar.scraper.db.DatabaseService;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Epic("REST API")
@Feature("Tendencias / ML Ops")
@Story("Tendencias / historial")
@DisplayName("TendenciasController — Tendencias & historial endpoints")
class TendenciasHistorialTest {

    private ScraperService service;
    private ResultAggregator aggregator;
    private DatabaseService db;
    private ar.scraper.ml.CategoriaStatsPort categoriaStats;
    private HistorialPort historial;
    private GroupingService grouping;
    private TendenciasController controller;
    private ComparadorController comparador;

    @BeforeEach
    void setUp() {
        wireController();
    }

    @Step("Wire TendenciasController with mocked collaborators")
    private void wireController() {
        service               = mock(ScraperService.class);
        aggregator            = mock(ResultAggregator.class);
        db                    = mock(DatabaseService.class);
        categoriaStats        = mock(ar.scraper.ml.CategoriaStatsPort.class);
        when(db.categoriaStats()).thenReturn(categoriaStats);
        historial             = mock(HistorialPort.class);
        when(db.historial()).thenReturn(historial);
        grouping              = mock(GroupingService.class);
        controller = new TendenciasController(service, db.categoriaStats(), db.historial(), aggregator);
        comparador = new ComparadorController(service, db.preciosExternos(), new ar.scraper.web.cache.CatalogoDerivadoCache(service, grouping));
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

        ApiException e = Wire.apiError(() -> controller.tendencias());

        assertThat(e.status().value()).isEqualTo(503);
        assertThat(e.code()).isEqualTo("ml_failed");
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
        tendencias.put("topCategoria", "Zapatilla");
        when(aggregator.getLastMlOutput()).thenReturn(ml);
        when(categoriaStats.cargarCategoriaStats()).thenReturn(Map.of());

        var resp = controller.tendencias();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Wire.data(resp).get("topCategoria").asText()).isEqualTo("Zapatilla");
    }

    // ── GET /api/historial ───────────────────────────────────────────────

    @Test
    void historialReturns204WhenNoHistoryFound() {
        when(historial.cargarHistorial("https://sitio.com/p/1")).thenReturn(List.of());

        var resp = controller.historial("https://sitio.com/p/1");

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void historialReturnsPuntosAndStatsWhenMultiplePricePoints() throws Exception {
        var entries = new ArrayList<Map<String, Object>>();
        entries.add(punto("2025-01-01", 10000.0));
        entries.add(punto("2025-02-01", 12000.0));
        entries.add(punto("2025-03-01", 9000.0));
        when(historial.cargarHistorial("https://a.com/1")).thenReturn(entries);

        var resp = controller.historial("https://a.com/1");
        JsonNode body = Wire.data(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("puntos").size()).isEqualTo(3);
        assertThat(body.get("min").asDouble()).isEqualTo(9000.0);
        assertThat(body.get("max").asDouble()).isEqualTo(12000.0);
    }

    @Test
    void historialReturnsSinglePuntoWithoutStatsWhenOnlyOnePoint() throws Exception {
        var entries = new ArrayList<Map<String, Object>>();
        entries.add(punto("2025-01-01", 10000.0));
        when(historial.cargarHistorial("https://a.com/1")).thenReturn(entries);

        var resp = controller.historial("https://a.com/1");
        JsonNode body = Wire.data(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("puntos").size()).isEqualTo(1);
        assertThat(body.has("min")).isFalse();
    }

    // ── GET /api/grupos ──────────────────────────────────────────────────

    @Test
    void gruposReturns204WhenNoLastResult() {
        when(service.getLastResult()).thenReturn(null);

        var resp = comparador.grupos(null, null, null, null, 2, 0, 20);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void gruposReturns200WithPaginatedGroupsWhenResultExists() throws Exception {
        when(service.getLastResult()).thenReturn(mockResult(List.of()));
        when(grouping.agrupar(any(), anyBoolean())).thenReturn(List.of());

        var resp = comparador.grupos(null, null, null, null, 2, 0, 20);
        JsonNode body = Wire.body(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("page").get("total").asInt()).isEqualTo(0);
        assertThat(body.get("data").isArray()).isTrue();
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
