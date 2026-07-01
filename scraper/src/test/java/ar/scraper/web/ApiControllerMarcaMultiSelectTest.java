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
 * Unit tests for the {@code marca} query param on {@code GET /api/data} changing
 * from a single optional {@code String} to an optional {@code List<String>}, mirroring
 * the existing {@code subCategoria} OR-match pattern (catalogo-filter-subbar-redesign, Phase 1).
 * Mirrors {@code ApiControllerPrecioRangeTest}'s convention: {@code ApiController} is a plain
 * {@code @RestController} POJO instantiated directly with Mockito-mocked collaborators.
 */
class ApiControllerMarcaMultiSelectTest {

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

    private Product producto(String url, String marca) {
        return new Product("Sitio", "Producto " + url, 10000.0, null, url, "img",
                "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, marca, "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY);
    }

    private AggregatedResult resultFor(Product... productos) {
        List<Product> lista = List.of(productos);
        double min = lista.stream().mapToDouble(Product::precio).min().orElse(0);
        double max = lista.stream().mapToDouble(Product::precio).max().orElse(0);
        return new AggregatedResult(lista, Map.of("Sitio", lista.size()), Map.of(),
                ResultAggregator.calcularFacets(lista), min, max);
    }

    // ── Multiple marca values OR-match ──────────────────────────────────

    @Test
    void selectingMultipleBrandsReturnsTheirUnion() {
        Product nike   = producto("https://site.com/nike", "Nike");
        Product adidas = producto("https://site.com/adidas", "Adidas");
        Product puma   = producto("https://site.com/puma", "Puma");
        when(service.getLastResult()).thenReturn(resultFor(nike, adidas, puma));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null,
                List.of("Nike", "Adidas"), null, null, null, null, "precio_asc", null, null, null, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(2);
        List<String> urls = List.of(productos.get(0).path("url").asText(), productos.get(1).path("url").asText());
        assertThat(urls).containsExactlyInAnyOrder("https://site.com/nike", "https://site.com/adidas");
    }

    // ── Zero brands selected applies no filter ──────────────────────────

    @Test
    void emptyMarcaListAppliesNoFilter() {
        Product nike   = producto("https://site.com/nike2", "Nike");
        Product adidas = producto("https://site.com/adidas2", "Adidas");
        when(service.getLastResult()).thenReturn(resultFor(nike, adidas));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null,
                List.of(), null, null, null, null, "precio_asc", null, null, null, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(2);
    }

    @Test
    void absentMarcaParamAppliesNoFilter() {
        Product nike   = producto("https://site.com/nike3", "Nike");
        Product adidas = producto("https://site.com/adidas3", "Adidas");
        when(service.getLastResult()).thenReturn(resultFor(nike, adidas));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null,
                null, null, null, null, null, "precio_asc", null, null, null, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(2);
    }

    // ── Single marca value still works ──────────────────────────────────

    @Test
    void singleMarcaValueStillWorks() {
        Product nike   = producto("https://site.com/nike4", "Nike");
        Product adidas = producto("https://site.com/adidas4", "Adidas");
        when(service.getLastResult()).thenReturn(resultFor(nike, adidas));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null,
                List.of("Nike"), null, null, null, null, "precio_asc", null, null, null, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(1);
        assertThat(productos.get(0).path("url").asText()).isEqualTo("https://site.com/nike4");
    }

    // ── Case-insensitive, accent-preserving exact match ─────────────────

    @Test
    void brandMatchIsCaseInsensitive() {
        Product nike = producto("https://site.com/nike5", "Nike");
        when(service.getLastResult()).thenReturn(resultFor(nike));

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null,
                List.of("nike"), null, null, null, null, "precio_asc", null, null, null, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(1);
    }
}
