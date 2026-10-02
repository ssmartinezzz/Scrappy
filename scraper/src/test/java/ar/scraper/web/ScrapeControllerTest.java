package ar.scraper.web;

import ar.scraper.web.support.Wire;
import ar.scraper.model.PersistenciaException;

import ar.scraper.scrape.ScraperStatus;


import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.Facets;
import ar.scraper.catalog.ProductPort;
import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Epic("REST API")
@Feature("Sitios / Config / Wiring")
@Story("Status / scrape")
@DisplayName("ScrapeController / DbAdminController — Status, scrape & wipe endpoints")
class ScrapeControllerTest {

    private ScraperService service;
    private ScraperConfig config;
    private ResultAggregator aggregator;
    private ar.scraper.ml.MlOutputPort mlOutput;
    private ProductPort productos;
    private ScrapeController controller;
    private DbAdminController dbAdmin;

    @BeforeEach
    void setUp() {
        wireController();
    }

    @Step("Wire controllers with mocked collaborators")
    private void wireController() {
        service               = mock(ScraperService.class);
        config                = mock(ScraperConfig.class);
        aggregator            = mock(ResultAggregator.class);
        mlOutput              = mock(ar.scraper.ml.MlOutputPort.class);
        productos             = mock(ProductPort.class);
        controller = new ScrapeController(service, config, new ScrapeStatusView(service));
        dbAdmin = new DbAdminController(service, mlOutput, productos, aggregator);
    }

    // ── GET /api/status ──────────────────────────────────────────────────

    @Test
    void statusReturnsIdleWithNoDataWhenLastResultIsNull() {
        when(service.getStatus()).thenReturn(ScraperStatus.IDLE);
        when(service.getStatusMsg()).thenReturn("Listo");
        when(service.getLastResult()).thenReturn(null);
        when(service.getProgressData()).thenReturn(null);

        var resp = controller.status();
        JsonNode body = Wire.data(resp);

        assertThat(body.get("status").asText()).isEqualTo("IDLE");
        assertThat(body.get("tieneData").asBoolean()).isFalse();
        assertThat(body.has("total")).isFalse();
    }

    @Test
    void statusReturnsTieneDataTrueWithCountWhenResultExists() {
        var products = List.of(producto("https://a.com/1", 1000));
        var result   = mockResult(products);
        when(service.getStatus()).thenReturn(ScraperStatus.DONE);
        when(service.getStatusMsg()).thenReturn("Finalizado");
        when(service.getLastResult()).thenReturn(result);
        when(service.getProgressData()).thenReturn(null);
        when(service.getUltimasCategoriasRefinadas()).thenReturn(3);

        var resp = controller.status();
        JsonNode body = Wire.data(resp);

        assertThat(body.get("tieneData").asBoolean()).isTrue();
        assertThat(body.get("total").asInt()).isEqualTo(1);
        assertThat(body.get("mlRefinadas").asInt()).isEqualTo(3);
    }

    @Test
    void statusIncludesProgresoBlockWhenProgressDataPresent() {
        when(service.getStatus()).thenReturn(ScraperStatus.RUNNING);
        when(service.getStatusMsg()).thenReturn("Scrapeando...");
        when(service.getLastResult()).thenReturn(null);
        var pd = new ScraperService.ProgressData(3, 1, 50,
                List.of(new ScraperService.SitioProgress("Sporting",
                        ScraperService.SitioEstado.EN_CURSO, 50, null, 1500)));
        when(service.getProgressData()).thenReturn(pd);

        var resp = controller.status();
        JsonNode body = Wire.data(resp);

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
        JsonNode body = Wire.data(resp);

        assertThat(body.get("iniciado").asBoolean()).isTrue();
        assertThat(body.get("mensaje").asText()).contains("iniciado");
    }

    @Test
    void scrapeReturnsiniciadoFalseWhenAlreadyRunning() {
        when(service.iniciarScraping(isNull(), eq(false))).thenReturn(false);

        var resp = controller.scrape(null, null, null, null, false);
        JsonNode body = Wire.data(resp);

        assertThat(body.get("iniciado").asBoolean()).isFalse();
        assertThat(body.get("mensaje").asText()).contains("curso");
    }

    @Test
    void scrapeSetsPrecioConfigBeforeLaunching() {
        when(service.iniciarScraping(any(), eq(false))).thenReturn(true);

        Allure.parameter("precioMin", 500.0);
        Allure.parameter("precioMax", 80000.0);
        controller.scrape(500.0, 80000.0, null, null, false);

        verify(config).setPrecioMinimo(500.0);
        verify(config).setPrecioMaximo(80000.0);
    }

    @Test
    void scrapeLegadoPrecioParamSetsPrecioMaximo() {
        when(service.iniciarScraping(any(), eq(false))).thenReturn(true);

        Allure.parameter("precio", 999.0);
        controller.scrape(null, null, 999.0, null, false);

        verify(config).setPrecioMaximo(999.0);
        verify(config, never()).setPrecioMinimo(anyDouble());
    }

    // ── DELETE /api/db/productos ─────────────────────────────────────────

    @Test
    void limpiarProductosReturns409WhenScrapingRunning() {
        when(service.getStatus()).thenReturn(ScraperStatus.RUNNING);

        var resp = Wire.answer(() -> dbAdmin.limpiarProductos());

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(mlOutput, productos, aggregator);
    }

    @Test
    void limpiarProductosReturns200AndClearsStateWhenIdle() throws Exception {
        when(service.getStatus()).thenReturn(ScraperStatus.IDLE);

        var resp = dbAdmin.limpiarProductos();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(productos).limpiarProductos();
        verify(service).clearLastResult();
        verify(aggregator).clearMlOutput();
    }

    @Test
    void limpiarProductosReturns500OnDbException() throws Exception {
        when(service.getStatus()).thenReturn(ScraperStatus.IDLE);
        doThrow(new PersistenciaException("DB error")).when(productos).limpiarProductos();

        var resp = Wire.answer(() -> dbAdmin.limpiarProductos());

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(Wire.error(resp).path("code").asText()).isEqualTo("error_interno");
        assertThat(Wire.error(resp).path("message").asText()).doesNotContain("DB error");
    }

    // ── DELETE /api/db/ml ────────────────────────────────────────────────

    @Test
    void limpiarMlReturns409WhenScrapingRunning() {
        when(service.getStatus()).thenReturn(ScraperStatus.RUNNING);

        var resp = Wire.answer(() -> dbAdmin.limpiarMl());

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(mlOutput, productos, aggregator);
    }

    @Test
    void limpiarMlReturns200AndClearsDataWhenIdle() throws Exception {
        when(service.getStatus()).thenReturn(ScraperStatus.IDLE);

        var resp = dbAdmin.limpiarMl();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(mlOutput).limpiarMlOutput();
        verify(aggregator).clearMlOutput();
    }

    @Test
    void limpiarMlReturns500OnDbException() throws Exception {
        when(service.getStatus()).thenReturn(ScraperStatus.IDLE);
        doThrow(new PersistenciaException("DB error")).when(mlOutput).limpiarMlOutput();

        var resp = Wire.answer(() -> dbAdmin.limpiarMl());

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(Wire.error(resp).path("code").asText()).isEqualTo("error_interno");
        assertThat(Wire.error(resp).path("message").asText()).doesNotContain("DB error");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private AggregatedResult mockResult(List<Product> products) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(products, Map.of(), Map.of(), facets, 0, 0);
    }

    private Product producto(String url, double precio) {
        return Product.builder()
                .sitio("Sitio")
                .nombre("Producto")
                .precio(precio)
                .precioOriginal(null)
                .url(url)
                .imagenUrl("img")
                .categoria("Remera")
                .genero("unisex")
                .talles(List.of())
                .ml(Product.MlScore.EMPTY)
                .marca("Marca")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY)
                .build();
    }
}
