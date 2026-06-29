package ar.scraper.web;

import ar.scraper.aggregator.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApiControllerSitiosConfigTest {

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

    // ── POST /api/sitios ─────────────────────────────────────────────────

    @Test
    void agregarSitioReturns400WhenNombreBlank() {
        var resp = controller.agregarSitio(Map.of("nombre", "", "url", "http://x.com"));
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(body.get("ok").asBoolean()).isFalse();
        verify(service, never()).agregarSitio(any(), any(), any());
    }

    @Test
    void agregarSitioReturns400WhenUrlBlank() {
        var resp = controller.agregarSitio(Map.of("nombre", "MiSitio", "url", ""));
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(body.get("ok").asBoolean()).isFalse();
    }

    @Test
    void agregarSitioReturns200AndDelegatesToServiceWhenValid() {
        var resp = controller.agregarSitio(
                Map.of("nombre", "MiSitio", "url", "https://misitiio.com", "plataforma", "shopify"));
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("ok").asBoolean()).isTrue();
        verify(service).agregarSitio("MiSitio", "https://misitiio.com", "shopify");
    }

    @Test
    void agregarSitioPrependsHttpsWhenUrlHasNoScheme() {
        controller.agregarSitio(Map.of("nombre", "S", "url", "sitio.com.ar"));
        verify(service).agregarSitio("S", "https://sitio.com.ar", "tiendanube");
    }

    @Test
    void agregarSitioDefaultsPlatformToTiendanubeWhenNotProvided() {
        controller.agregarSitio(Map.of("nombre", "S", "url", "http://s.com"));
        verify(service).agregarSitio("S", "http://s.com", "tiendanube");
    }

    // ── DELETE /api/sitios/{nombre} ──────────────────────────────────────

    @Test
    void eliminarSitioReturnsOkTrueWhenFound() {
        when(service.eliminarSitio("eldon")).thenReturn(true);

        var resp = controller.eliminarSitio("eldon");
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("mensaje").asText()).contains("eliminado");
    }

    @Test
    void eliminarSitioReturnsOkFalseWhenNotFound() {
        when(service.eliminarSitio("inexistente")).thenReturn(false);

        var resp = controller.eliminarSitio("inexistente");
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("ok").asBoolean()).isFalse();
        assertThat(body.get("mensaje").asText()).contains("no encontrado");
    }

    // ── PUT /api/config ──────────────────────────────────────────────────

    @Test
    void updateConfigSetsPrecioMinimo() {
        var resp = controller.updateConfig(Map.of("precioMinimo", 1500));
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        verify(config).setPrecioMinimo(1500.0);
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("precioMinimo").asDouble()).isEqualTo(1500.0);
    }

    @Test
    void updateConfigSetsPrecioMaximo() {
        var resp = controller.updateConfig(Map.of("precioMaximo", 99999));
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        verify(config).setPrecioMaximo(99999.0);
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("precioMaximo").asDouble()).isEqualTo(99999.0);
    }

    @Test
    void updateConfigSetsBothPreciosWhenBothPresent() {
        controller.updateConfig(Map.of("precioMinimo", 100, "precioMaximo", 50000));

        verify(config).setPrecioMinimo(100.0);
        verify(config).setPrecioMaximo(50000.0);
    }

    // ── GET /api/csv ─────────────────────────────────────────────────────

    @Test
    void csvReturns204WhenContentIsBlank() throws Exception {
        when(service.generarCsv()).thenReturn("");

        var resp = controller.csv();

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void csvReturns200WithContentDispositionWhenContentPresent() throws Exception {
        when(service.generarCsv()).thenReturn("sitio,nombre,precio\nSporting,Zapatillas,50000");

        var resp = controller.csv();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("ofertas.csv");
        assertThat(resp.getBody()).contains("Zapatillas");
    }
}
