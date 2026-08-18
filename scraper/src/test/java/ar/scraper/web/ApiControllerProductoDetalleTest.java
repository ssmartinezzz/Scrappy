package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@code GET /api/producto?url=} — the read behind the standalone price-history
 * view. It exists alongside {@code /api/historial} rather than replacing it
 * because the two answer different questions and, critically, disagree on what
 * "no history" means.
 *
 * <p>{@code /api/historial} returns {@code 204 No Content} when a product has
 * no recorded points, which is right for the widgets that consume it — a
 * sparkline with nothing to draw should render nothing. A standalone page
 * cannot use that: a product that exists but has been scraped once is a page
 * that must still render, showing the product and saying the history is not
 * there yet. So this endpoint answers {@code 200} with an empty
 * {@code puntos} array, and reserves {@code 404} for a product that genuinely
 * does not exist.</p>
 *
 * <p>The product is read from the DATABASE, not from the in-memory catalog
 * snapshot: the page is deep-linkable, and a soft-deleted product must still
 * be inspectable — that is exactly when its price history is interesting.</p>
 */
@Epic("REST API")
@Feature("Catálogo")
@DisplayName("ApiController — GET /api/producto (detalle + historial)")
class ApiControllerProductoDetalleTest {

    private static final String URL = "https://site.com/remera-negra";
    /** El handle corto de URL — mismo valor que calcula la columna generada de V25. */
    private static final String KEY = ProductKeyTestBridge.of(URL);

    private ScraperService service;
    private DatabaseService db;
    private ApiController controller;

    @BeforeEach
    void setUp() {
        service = mock(ScraperService.class);
        db      = mock(DatabaseService.class);
        controller = new ApiController(service, mock(InflacionService.class), mock(ScraperConfig.class),
                mock(ResultAggregator.class), db, mock(GroupingService.class), mock(PythonRunner.class),
                mock(OutfitService.class), mock(RecommendationService.class));
    }

    private Product producto() {
        return new Product("Freres", "Remera Negra", 15990, 19990.0, URL,
                "https://img.example/r.jpg", "Remera", "hombre", List.of("M", "L"),
                Product.MlScore.EMPTY, "Nike", "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1);
    }

    private static Map<String, Object> punto(String fecha, double precio) {
        return Map.of("fecha", fecha, "precio", precio);
    }

    // ── Producto inexistente ─────────────────────────────────────────────

    @Test
    @Story("404 cuando el producto no existe")
    void unknownProductIsA404() {
        when(db.obtenerProductoPorKey(KEY)).thenReturn(Optional.empty());

        var resp = controller.productoDetalle(KEY);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verify(db, never()).cargarHistorial(anyString());
    }

    // ── Producto sin historial: 200, no 204 ──────────────────────────────

    @Test
    @Story("un producto sin historial igual renderiza")
    void productWithoutHistoryStillReturnsTheProduct() {
        when(db.obtenerProductoPorKey(KEY)).thenReturn(Optional.of(producto()));
        when(db.cargarHistorial(URL)).thenReturn(List.of());

        var resp = controller.productoDetalle(KEY);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("producto").path("nombre").asText()).isEqualTo("Remera Negra");
        assertThat(body.path("producto").path("url").asText()).isEqualTo(URL);
        assertThat(body.path("historial").path("puntos")).isEmpty();
    }

    @Test
    @Story("un solo punto tampoco alcanza para stats, pero no rompe")
    void aSinglePointYieldsNoStatsButStillRenders() {
        when(db.obtenerProductoPorKey(KEY)).thenReturn(Optional.of(producto()));
        when(db.cargarHistorial(URL)).thenReturn(List.of(punto("2026-05-20", 15990)));

        JsonNode body = (JsonNode) controller.productoDetalle(KEY).getBody();

        assertThat(body.path("historial").path("puntos")).hasSize(1);
        assertThat(body.path("historial").has("min")).isFalse();
    }

    // ── Camino feliz ─────────────────────────────────────────────────────

    @Test
    @Story("producto + puntos + stats en una sola respuesta")
    void productAndHistoryComeBackTogether() {
        when(db.obtenerProductoPorKey(KEY)).thenReturn(Optional.of(producto()));
        when(db.cargarHistorial(URL)).thenReturn(List.of(
                punto("2026-05-20", 20000),
                punto("2026-05-28", 15000),
                punto("2026-06-04", 16000)));

        JsonNode body = (JsonNode) controller.productoDetalle(KEY).getBody();
        JsonNode hist = body.path("historial");

        assertThat(body.path("producto").path("precio").asDouble()).isEqualTo(15990);
        assertThat(body.path("producto").path("sitio").asText()).isEqualTo("Freres");
        assertThat(hist.path("puntos")).hasSize(3);
        assertThat(hist.path("puntos").get(0).path("fecha").asText()).isEqualTo("2026-05-20");
        assertThat(hist.path("min").asDouble()).isEqualTo(15000);
        assertThat(hist.path("max").asDouble()).isEqualTo(20000);
        // Del primero al último: 20000 -> 16000 = -20%
        assertThat(hist.path("deltaPct").asDouble()).isEqualTo(-20.0);
    }

    @Test
    @Story("la respuesta trae el handle, para que el frontend pueda re-linkear")
    void theResponseCarriesTheShortHandle() {
        when(db.obtenerProductoPorKey(KEY)).thenReturn(Optional.of(producto()));
        when(db.cargarHistorial(URL)).thenReturn(List.of());

        JsonNode body = (JsonNode) controller.productoDetalle(KEY).getBody();

        assertThat(body.path("producto").path("key").asText()).isEqualTo(KEY);
        assertThat(KEY).hasSize(16);
    }

    // ── El contrato viejo no se toca ─────────────────────────────────────

    @Test
    @Story("/api/historial sigue devolviendo 204 sin historial")
    void theOlderHistorialEndpointKeepsIts204() {
        when(db.cargarHistorial(URL)).thenReturn(List.of());

        assertThat(controller.historial(URL).getStatusCode().value()).isEqualTo(204);
    }

    @Test
    @Story("/api/historial sigue devolviendo los mismos puntos y stats")
    void theOlderHistorialEndpointKeepsItsShape() {
        when(db.cargarHistorial(URL)).thenReturn(List.of(
                punto("2026-05-20", 20000),
                punto("2026-06-04", 16000)));

        JsonNode body = (JsonNode) controller.historial(URL).getBody();

        assertThat(body.path("puntos")).hasSize(2);
        assertThat(body.path("min").asDouble()).isEqualTo(16000);
        assertThat(body.path("deltaPct").asDouble()).isEqualTo(-20.0);
    }
}
