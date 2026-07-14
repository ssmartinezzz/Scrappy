package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import ar.scraper.model.Product.SenalFinanciacion;
import ar.scraper.model.Product.VisualAttrs;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the additive image-derived visual-attribute facets/filters
 * (fashion-image-classification PR6, T6.5-T6.8): {@code GET /api/facets} and
 * {@code GET /api/data} expose/filter by {@code fit}/{@code estampado}/
 * {@code escote}/{@code colorDominante} alongside the existing talles/generos/
 * categorias/marcas/badges/subCategorias facets and filters, without
 * regressing any of them. Mirrors {@code ApiControllerMarcaMultiSelectTest}'s
 * convention: {@code ApiController} is a plain {@code @RestController} POJO
 * instantiated directly with Mockito-mocked collaborators.
 */
@Epic("REST API")
@Feature("Filtros / Facets")
@Story("Visual attributes (fit/estampado/escote/color_dominante)")
@DisplayName("ApiController — visual attribute facets and filters")
class ApiControllerVisualAttrsTest {

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
        service                = mock(ScraperService.class);
        inflacionService       = mock(InflacionService.class);
        config                 = mock(ScraperConfig.class);
        aggregator             = mock(ResultAggregator.class);
        db                     = mock(DatabaseService.class);
        grouping               = mock(GroupingService.class);
        pythonRunner           = mock(PythonRunner.class);
        outfitService          = mock(OutfitService.class);
        recommendationService  = mock(RecommendationService.class);
        controller = new ApiController(service, inflacionService, config, aggregator,
                db, grouping, pythonRunner, outfitService, recommendationService);
    }

    private Product producto(String url, VisualAttrs visual) {
        return new Product("Sitio", "Producto " + url, 10000.0, null, url, "img",
                "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1, "", visual);
    }

    private AggregatedResult resultFor(Product... productos) {
        List<Product> lista = List.of(productos);
        double min = lista.stream().mapToDouble(Product::precio).min().orElse(0);
        double max = lista.stream().mapToDouble(Product::precio).max().orElse(0);
        return new AggregatedResult(lista, Map.of("Sitio", lista.size()), Map.of(),
                ResultAggregator.calcularFacets(lista), min, max);
    }

    // ── T6.5: GET /api/facets includes fit/estampado/escote/colorDominante ──

    @Test
    void facetsIncludesVisualAttributeGroupsAlongsideExisting() {
        Product oversize = producto("https://site.com/a", new VisualAttrs("oversize", "estampado", "cuello redondo", "azul"));
        Product regular   = producto("https://site.com/b", new VisualAttrs("regular", "liso", "en v", "rojo"));
        when(service.getLastResult()).thenReturn(resultFor(oversize, regular));

        ResponseEntity<?> resp = controller.facets();
        JsonNode body = (JsonNode) resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        // Existing facets remain present/unaffected
        assertThat(body.has("talles")).isTrue();
        assertThat(body.has("categorias")).isTrue();
        // New additive facets
        assertThat(body.path("fits").get("oversize").asLong()).isEqualTo(1L);
        assertThat(body.path("fits").get("regular").asLong()).isEqualTo(1L);
        assertThat(body.path("estampados").get("estampado").asLong()).isEqualTo(1L);
        assertThat(body.path("escotes").get("cuello redondo").asLong()).isEqualTo(1L);
        assertThat(body.path("colorDominantes").get("azul").asLong()).isEqualTo(1L);
    }

    @Test
    void dataMetaFacetsIncludesVisualAttributeGroups() {
        Product oversize = producto("https://site.com/c", new VisualAttrs("oversize", "", "", ""));
        when(service.getLastResult()).thenReturn(resultFor(oversize));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null,
                null, null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);
        JsonNode body = (JsonNode) resp.getBody();

        assertThat(body.path("meta").path("facets").path("fits").get("oversize").asLong()).isEqualTo(1L);
    }

    // ── T6.7: GET /api/data?estampado=X (and fit/escote/colorDominante) filters ──

    @Test
    void filtersByEstampado() {
        Product estampado = producto("https://site.com/estampado", new VisualAttrs("", "estampado", "", ""));
        Product liso       = producto("https://site.com/liso", new VisualAttrs("", "liso", "", ""));
        when(service.getLastResult()).thenReturn(resultFor(estampado, liso));

        Allure.parameter("estampado", "estampado");
        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null,
                null, null, null, null, null, "precio_asc", null, null, null, null,
                null, "estampado", null, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(1);
        assertThat(productos.get(0).path("url").asText()).isEqualTo("https://site.com/estampado");
    }

    @Test
    void filtersByFit() {
        Product oversize = producto("https://site.com/oversize", new VisualAttrs("oversize", "", "", ""));
        Product regular   = producto("https://site.com/regular", new VisualAttrs("regular", "", "", ""));
        when(service.getLastResult()).thenReturn(resultFor(oversize, regular));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null,
                null, null, null, null, null, "precio_asc", null, null, null, null,
                "oversize", null, null, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(1);
        assertThat(productos.get(0).path("url").asText()).isEqualTo("https://site.com/oversize");
    }

    @Test
    void filtersByEscote() {
        Product cuelloRedondo = producto("https://site.com/redondo", new VisualAttrs("", "", "cuello redondo", ""));
        Product enV            = producto("https://site.com/v", new VisualAttrs("", "", "en v", ""));
        when(service.getLastResult()).thenReturn(resultFor(cuelloRedondo, enV));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null,
                null, null, null, null, null, "precio_asc", null, null, null, null,
                null, null, "cuello redondo", null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(1);
        assertThat(productos.get(0).path("url").asText()).isEqualTo("https://site.com/redondo");
    }

    @Test
    void filtersByColorDominante() {
        Product azul = producto("https://site.com/azul", new VisualAttrs("", "", "", "azul"));
        Product rojo = producto("https://site.com/rojo", new VisualAttrs("", "", "", "rojo"));
        when(service.getLastResult()).thenReturn(resultFor(azul, rojo));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null,
                null, null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, "azul");

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(1);
        assertThat(productos.get(0).path("url").asText()).isEqualTo("https://site.com/azul");
    }

    @Test
    void absentVisualAttrParamsApplyNoFilter() {
        Product a = producto("https://site.com/x", new VisualAttrs("oversize", "estampado", "cuello redondo", "azul"));
        Product b = producto("https://site.com/y", VisualAttrs.EMPTY);
        when(service.getLastResult()).thenReturn(resultFor(a, b));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null,
                null, null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(2);
    }

    @Test
    void visualAttrFilterIsCaseInsensitive() {
        Product azul = producto("https://site.com/azul2", new VisualAttrs("", "", "", "azul"));
        when(service.getLastResult()).thenReturn(resultFor(azul));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null,
                null, null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, "AZUL");

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(1);
    }

    // ── Regression: legacy 17-arg call sites (pre-PR6) still compile/behave ──

    @Test
    void legacySeventeenArgDataCallStillAppliesExistingFiltersUnaffected() {
        Product nike = producto("https://site.com/nike-legacy", VisualAttrs.EMPTY);
        when(service.getLastResult()).thenReturn(resultFor(nike));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null,
                List.of("Marca"), null, null, null, null, "precio_asc", null, null, null, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(1);
    }
}
