package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApiControllerStatusScrapeTest {

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

    // ── GET /api/status ──────────────────────────────────────────────────

    @Test
    void statusReturnsIdleWithNoDataWhenLastResultIsNull() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        when(service.getStatusMsg()).thenReturn("Listo");
        when(service.getLastResult()).thenReturn(null);
        when(service.getProgressData()).thenReturn(null);

        var resp = controller.status();
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("status").asText()).isEqualTo("IDLE");
        assertThat(body.get("tieneData").asBoolean()).isFalse();
        assertThat(body.has("total")).isFalse();
    }

    @Test
    void statusReturnsTieneDataTrueWithCountWhenResultExists() {
        var products = List.of(producto("https://a.com/1", 1000));
        var result   = mockResult(products);
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.DONE);
        when(service.getStatusMsg()).thenReturn("Finalizado");
        when(service.getLastResult()).thenReturn(result);
        when(service.getProgressData()).thenReturn(null);
        when(service.getUltimasCategoriasRefinadas()).thenReturn(3);

        var resp = controller.status();
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("tieneData").asBoolean()).isTrue();
        assertThat(body.get("total").asInt()).isEqualTo(1);
        assertThat(body.get("mlRefinadas").asInt()).isEqualTo(3);
    }

    @Test
    void statusIncludesProgresoBlockWhenProgressDataPresent() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.RUNNING);
        when(service.getStatusMsg()).thenReturn("Scrapeando...");
        when(service.getLastResult()).thenReturn(null);
        var pd = new ScraperService.ProgressData(3, 1, 50,
                List.of(new ScraperService.SitioProgress("Sporting",
                        ScraperService.SitioEstado.EN_CURSO, 50, null, 1500)));
        when(service.getProgressData()).thenReturn(pd);

        var resp = controller.status();
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.has("progreso")).isTrue();
        assertThat(body.path("progreso").get("total").asInt()).isEqualTo(3);
        assertThat(body.path("progreso").get("completados").asInt()).isEqualTo(1);
        assertThat(body.path("progreso").get("sitios").size()).isEqualTo(1);
    }

    // ── POST /api/scrape ─────────────────────────────────────────────────

    @Test
    void scrapeReturnsiniciadoTrueWhenScrapingStarts() {
        when(service.iniciarScraping(isNull(), eq(false))).thenReturn(true);

        var resp = controller.scrape(null, null, null, null, false);
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("iniciado").asBoolean()).isTrue();
        assertThat(body.get("mensaje").asText()).contains("iniciado");
    }

    @Test
    void scrapeReturnsiniciadoFalseWhenAlreadyRunning() {
        when(service.iniciarScraping(isNull(), eq(false))).thenReturn(false);

        var resp = controller.scrape(null, null, null, null, false);
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("iniciado").asBoolean()).isFalse();
        assertThat(body.get("mensaje").asText()).contains("curso");
    }

    @Test
    void scrapeSetsPrecioConfigBeforeLaunching() {
        when(service.iniciarScraping(any(), eq(false))).thenReturn(true);

        controller.scrape(500.0, 80000.0, null, null, false);

        verify(config).setPrecioMinimo(500.0);
        verify(config).setPrecioMaximo(80000.0);
    }

    @Test
    void scrapeLegadoPrecioParamSetsPrecioMaximo() {
        when(service.iniciarScraping(any(), eq(false))).thenReturn(true);

        controller.scrape(null, null, 999.0, null, false);

        verify(config).setPrecioMaximo(999.0);
        verify(config, never()).setPrecioMinimo(anyDouble());
    }

    // ── DELETE /api/db/productos ─────────────────────────────────────────

    @Test
    void limpiarProductosReturns409WhenScrapingRunning() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.RUNNING);

        var resp = controller.limpiarProductos();

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(db, aggregator);
    }

    @Test
    void limpiarProductosReturns200AndClearsStateWhenIdle() throws Exception {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);

        var resp = controller.limpiarProductos();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(db).limpiarProductos();
        verify(service).clearLastResult();
        verify(aggregator).clearMlOutput();
    }

    @Test
    void limpiarProductosReturns500OnDbException() throws Exception {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        doThrow(new SQLException("DB error")).when(db).limpiarProductos();

        var resp = controller.limpiarProductos();

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
    }

    // ── DELETE /api/db/ml ────────────────────────────────────────────────

    @Test
    void limpiarMlReturns409WhenScrapingRunning() {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.RUNNING);

        var resp = controller.limpiarMl();

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(db, aggregator);
    }

    @Test
    void limpiarMlReturns200AndClearsDataWhenIdle() throws Exception {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);

        var resp = controller.limpiarMl();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(db).limpiarMlOutput();
        verify(aggregator).clearMlOutput();
    }

    @Test
    void limpiarMlReturns500OnDbException() throws Exception {
        when(service.getStatus()).thenReturn(ScraperService.ScraperStatus.IDLE);
        doThrow(new SQLException("DB error")).when(db).limpiarMlOutput();

        var resp = controller.limpiarMl();

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private AggregatedResult mockResult(List<Product> products) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(products, Map.of(), Map.of(), facets, 0, 0);
    }

    private Product producto(String url, double precio) {
        return new Product("Sitio", "Producto", precio, null, url, "img",
                "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "Marca",
                "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY);
    }
}
