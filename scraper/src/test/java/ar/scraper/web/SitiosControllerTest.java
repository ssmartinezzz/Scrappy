package ar.scraper.web;

import ar.scraper.web.support.Wire;

import ar.scraper.indices.IndiceService;

import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Epic("REST API")
@Feature("Sitios / Config / Wiring")
@Story("Sitios config")
@DisplayName("SitiosController — Sitios & config CRUD")
class SitiosControllerTest {

    private ScraperService service;
    private IndiceService indiceService;
    private ScraperConfig config;
    private DatabaseService db;
    private SitiosController controller;
    private CatalogoController catalogo;

    @BeforeEach
    void setUp() {
        wireController();
    }

    @Step("Wire SitiosController with mocked collaborators")
    private void wireController() {
        service               = mock(ScraperService.class);
        indiceService      = mock(IndiceService.class);
        config                = mock(ScraperConfig.class);
        db                    = mock(DatabaseService.class);
        controller = new SitiosController(service, config);
        catalogo = new CatalogoController(service, db.presets(), db.historial(), db.catalogQuery(), db.productos(), config, indiceService);
    }

    // ── POST /api/sitios ─────────────────────────────────────────────────

    @Test
    void agregarSitioReturns400WhenNombreBlank() {
        var resp = Wire.answer(() -> controller.agregarSitio(Map.of("nombre", "", "url", "http://x.com")));
        JsonNode error = Wire.error(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(error.get("code").asText()).isEqualTo("solicitud_invalida");
        verify(service, never()).agregarSitio(any(), any(), any());
    }

    @Test
    void agregarSitioReturns400WhenUrlBlank() {
        var resp = Wire.answer(() -> controller.agregarSitio(Map.of("nombre", "MiSitio", "url", "")));
        JsonNode error = Wire.error(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(error.get("code").asText()).isEqualTo("solicitud_invalida");
    }

    @Test
    void agregarSitioReturns200AndDelegatesToServiceWhenValid() {
        var resp = controller.agregarSitio(
                Map.of("nombre", "MiSitio", "url", "https://misitiio.com", "plataforma", "shopify"));
        JsonNode body = Wire.data(resp);

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

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:5432", "http://169.254.169.254/latest/meta-data", "localhost:8080",
            "httpx://tienda.com", "http://10.0.0.5/admin", "http://2130706433/"})
    void agregarSitioReturns400WhenUrlTargetsAnInternalOrMalformedHost(String url) {
        var resp = Wire.answer(() -> controller.agregarSitio(Map.of("nombre", "S", "url", url)));
        JsonNode error = Wire.error(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(error.get("code").asText()).isEqualTo("solicitud_invalida");
        verify(service, never()).agregarSitio(any(), any(), any());
    }

    // ── DELETE /api/sitios/{nombre} ──────────────────────────────────────

    @Test
    void eliminarSitioReturnsOkTrueWhenFound() {
        when(service.eliminarSitio("eldon")).thenReturn(true);

        var resp = controller.eliminarSitio("eldon");
        JsonNode body = Wire.data(resp);

        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("mensaje").asText()).contains("eliminado");
    }

    @Test
    void eliminarSitioReturnsOkFalseWhenNotFound() {
        when(service.eliminarSitio("inexistente")).thenReturn(false);

        var resp = controller.eliminarSitio("inexistente");
        JsonNode body = Wire.data(resp);

        assertThat(body.get("ok").asBoolean()).isFalse();
        assertThat(body.get("mensaje").asText()).contains("no encontrado");
    }

    // ── PUT /api/config ──────────────────────────────────────────────────

    @Test
    void updateConfigSetsPrecioMinimo() {
        var resp = controller.updateConfig(Map.of("precioMinimo", 1500));
        JsonNode body = Wire.data(resp);

        verify(config).setPrecioMinimo(1500.0);
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("precioMinimo").asDouble()).isEqualTo(1500.0);
    }

    @Test
    void updateConfigSetsPrecioMaximo() {
        var resp = controller.updateConfig(Map.of("precioMaximo", 99999));
        JsonNode body = Wire.data(resp);

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

        var resp = catalogo.csv();

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void csvReturns200WithContentDispositionWhenContentPresent() throws Exception {
        when(service.generarCsv()).thenReturn("sitio,nombre,precio\nSporting,Zapatillas,50000");

        var resp = catalogo.csv();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("ofertas.csv");
        assertThat(resp.getBody()).contains("Zapatilla");
    }
}
