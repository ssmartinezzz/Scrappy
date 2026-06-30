package ar.scraper.web;

import ar.scraper.aggregator.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import ar.scraper.model.Product.SenalFinanciacion;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the additive {@code precioMin}/{@code precioMax} optional
 * query params on {@code GET /api/data} (frontend-redesign-responsive PR1,
 * Phase 2). Mirrors {@code ApiControllerFinanciacionTest}'s convention:
 * {@code ApiController} is a plain {@code @RestController} POJO instantiated
 * directly with Mockito-mocked collaborators.
 *
 * <p>Regression guard: omitting both params must preserve {@code /api/data}
 * behavior exactly as it was before this change — verified by
 * {@link #omittingBothParamsPreservesPriorBehaviorExactly()}.</p>
 */
class ApiControllerPrecioRangeTest {

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

        when(config.getMoneda()).thenReturn("ARS");
        when(db.cargarPresetActivo()).thenReturn(Optional.empty());
    }

    private Product producto(String url, double precio) {
        return new Product("Sitio", "Producto " + url, precio, null, url, "img",
                "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY);
    }

    /** Pack/combo product — {@code precioTotal} is the bundle price, not the per-unit price. */
    private Product productoPack(String url, double precioTotal, int cantidadUnidades) {
        return new Product("Sitio", "Producto " + url, precioTotal, null, url, "img",
                "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY, cantidadUnidades);
    }

    private AggregatedResult resultFor(Product... productos) {
        List<Product> lista = List.of(productos);
        double min = lista.stream().mapToDouble(Product::precio).min().orElse(0);
        double max = lista.stream().mapToDouble(Product::precio).max().orElse(0);
        return new AggregatedResult(lista, Map.of("Sitio", lista.size()), Map.of(),
                ResultAggregator.calcularFacets(lista), min, max);
    }

    // ── precioMin/precioMax filter products within range ───────────────────

    @Test
    void precioMinAndPrecioMaxFilterProductsWithinRangeInclusive() {
        Product barato = producto("https://site.com/barato", 5000);
        Product medio  = producto("https://site.com/medio", 15000);
        Product caro   = producto("https://site.com/caro", 50000);
        when(service.getLastResult()).thenReturn(resultFor(barato, medio, caro));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, 10000.0, 20000.0, null);

        JsonNode body = (JsonNode) resp.getBody();
        JsonNode productos = body.path("productos");
        assertThat(productos).hasSize(1);
        assertThat(productos.get(0).path("url").asText()).isEqualTo("https://site.com/medio");
        assertThat(body.path("meta").path("total").asInt()).isEqualTo(1);
    }

    @Test
    void precioMinAloneFiltersOutCheaperProducts() {
        Product barato = producto("https://site.com/barato2", 1000);
        Product caro   = producto("https://site.com/caro2", 90000);
        when(service.getLastResult()).thenReturn(resultFor(barato, caro));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, 5000.0, null, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(1);
        assertThat(productos.get(0).path("url").asText()).isEqualTo("https://site.com/caro2");
    }

    @Test
    void precioMaxAloneFiltersOutMoreExpensiveProducts() {
        Product barato = producto("https://site.com/barato3", 1000);
        Product caro   = producto("https://site.com/caro3", 90000);
        when(service.getLastResult()).thenReturn(resultFor(barato, caro));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, 5000.0, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(1);
        assertThat(productos.get(0).path("url").asText()).isEqualTo("https://site.com/barato3");
    }

    @Test
    void boundaryPricesEqualToMinOrMaxAreIncluded() {
        Product atMin = producto("https://site.com/atmin", 10000);
        Product atMax = producto("https://site.com/atmax", 20000);
        Product outside = producto("https://site.com/outside", 25000);
        when(service.getLastResult()).thenReturn(resultFor(atMin, atMax, outside));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, 10000.0, 20000.0, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(2);
    }

    // ── Pack/combo products are filtered by unit price, not total price ────

    @Test
    void packProductIsFilteredByUnitPriceNotTotalPrice() {
        // 3-pack at $36000 total => $12000/unit, falls inside [10000, 15000]
        Product packDentroDelRango = productoPack("https://site.com/pack-in", 36000, 3);
        // single unit at $36000, falls outside [10000, 15000]
        Product unidadFueraDelRango = producto("https://site.com/unidad-out", 36000);
        when(service.getLastResult()).thenReturn(resultFor(packDentroDelRango, unidadFueraDelRango));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, 10000.0, 15000.0, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(1);
        assertThat(productos.get(0).path("url").asText()).isEqualTo("https://site.com/pack-in");
    }

    @Test
    void packProductOutsideUnitPriceRangeIsExcludedEvenIfTotalPriceWouldMatch() {
        // 5-pack at $12000 total => $2400/unit, total $12000 would match [10000, 15000]
        // but the real per-unit price ($2400) does not — must be excluded.
        Product pack = productoPack("https://site.com/pack-cheap-unit", 12000, 5);
        when(service.getLastResult()).thenReturn(resultFor(pack));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, 10000.0, 15000.0, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).isEmpty();
    }

    // ── Backward compatibility: omitting both params ────────────────────────

    @Test
    void omittingBothParamsPreservesPriorBehaviorExactly() {
        Product a = producto("https://site.com/a", 1000);
        Product b = producto("https://site.com/b", 90000);
        when(service.getLastResult()).thenReturn(resultFor(a, b));

        ResponseEntity<?> respWithoutNewParams = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null);

        JsonNode body = (JsonNode) respWithoutNewParams.getBody();
        assertThat(body.path("productos")).hasSize(2);
        assertThat(body.path("meta").path("total").asInt()).isEqualTo(2);
        assertThat(body.path("meta").path("totalPaginas").asInt()).isEqualTo(1);
    }
}
