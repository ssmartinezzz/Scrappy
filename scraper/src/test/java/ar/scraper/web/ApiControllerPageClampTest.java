package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import ar.scraper.model.Product.SenalFinanciacion;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the lower-bound clamping of the {@code page} query param on
 * {@code GET /api/data}. Mirrors {@code ApiControllerPrecioRangeTest}'s
 * convention: {@code ApiController} is a plain {@code @RestController} POJO
 * instantiated directly with Mockito-mocked collaborators.
 */
@Epic("REST API")
@Feature("Filtros / Facets")
@Story("Page clamp")
@DisplayName("ApiController — page param lower-bound clamp")
class ApiControllerPageClampTest {

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

        when(config.getMoneda()).thenReturn("ARS");
        when(db.cargarPresetActivo()).thenReturn(Optional.empty());
    }

    @Step("Wire ApiController with mocked collaborators")
    private void wireController() {
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

    private Product producto(String url, double precio) {
        return new Product("Sitio", "Producto " + url, precio, null, url, "img",
                "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY);
    }

    private AggregatedResult resultFor(Product... productos) {
        List<Product> lista = List.of(productos);
        double min = lista.stream().mapToDouble(Product::precio).min().orElse(0);
        double max = lista.stream().mapToDouble(Product::precio).max().orElse(0);
        return new AggregatedResult(lista, Map.of("Sitio", lista.size()), Map.of(),
                ResultAggregator.calcularFacets(lista), min, max);
    }

    @Test
    void pageZeroReturnsSameResultAsPageOneWithoutError() {
        Product a = producto("https://site.com/a", 1000);
        Product b = producto("https://site.com/b", 2000);
        when(service.getLastResult()).thenReturn(resultFor(a, b));

        ResponseEntity<?> respZero = controller.data(0, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);
        ResponseEntity<?> respOne = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);

        assertThat(respZero.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode bodyZero = (JsonNode) respZero.getBody();
        JsonNode bodyOne = (JsonNode) respOne.getBody();
        assertThat(bodyZero.path("productos")).isEqualTo(bodyOne.path("productos"));
    }

    @Test
    void negativePageReturnsFirstPageWithoutError() {
        Product a = producto("https://site.com/c", 1000);
        Product b = producto("https://site.com/d", 2000);
        when(service.getLastResult()).thenReturn(resultFor(a, b));

        ResponseEntity<?> resp = controller.data(-3, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(2);
    }

    @Test
    void metaPaginaReflectsClampForPageZeroOrNegative() {
        Product a = producto("https://site.com/e", 1000);
        when(service.getLastResult()).thenReturn(resultFor(a));

        ResponseEntity<?> respZero = controller.data(0, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);
        ResponseEntity<?> respNeg = controller.data(-5, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);

        assertThat(((JsonNode) respZero.getBody()).path("meta").path("pagina").asInt()).isEqualTo(1);
        assertThat(((JsonNode) respNeg.getBody()).path("meta").path("pagina").asInt()).isEqualTo(1);
    }

    @Test
    void metaPaginaReflectsValidPageUnchanged() {
        Product a = producto("https://site.com/p1", 1000);
        Product b = producto("https://site.com/p2", 2000);
        Product c = producto("https://site.com/p3", 3000);
        when(service.getLastResult()).thenReturn(resultFor(a, b, c));

        ResponseEntity<?> resp = controller.data(3, 1, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);

        assertThat(((JsonNode) resp.getBody()).path("meta").path("pagina").asInt()).isEqualTo(3);
    }

    /**
     * Regression guard for the multiplication overflow class of bug: a huge
     * {@code page} value must not wrap {@code (page - 1) * size} into a
     * negative {@code int} and crash {@code subList}. The offset is widened
     * to {@code long} before being clamped down to {@code total}.
     */
    @Test
    void integerMaxValuePageDoesNotOverflowAndReturnsEmptyLastPage() {
        Product a = producto("https://site.com/f", 1000);
        when(service.getLastResult()).thenReturn(resultFor(a));

        ResponseEntity<?> resp = controller.data(Integer.MAX_VALUE, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).isEmpty();
    }
}
