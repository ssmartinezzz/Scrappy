package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import ar.scraper.testsupport.AllureSteps;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Epic("REST API")
@Feature("Favoritos")
@DisplayName("ApiController — Favoritos endpoints")
class ApiControllerFavoritosTest {

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
    }

    @Step("Wire ApiController with mocked collaborators")
    private void wireController() {
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

    // ── GET /api/favoritos ───────────────────────────────────────────────

    @Test
    @Story("GET /api/favoritos")
    void getFavoritosReturnsEmptyArrayWhenNoFavorites() {
        givenNoFavoritos();

        var resp = controller.getFavoritos();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(0);
    }

    @Step("Stub DB with no favoritos")
    private void givenNoFavoritos() {
        when(db.listarFavoritos()).thenReturn(List.of());
    }

    @Test
    @Story("GET /api/favoritos")
    void getFavoritosReturnsFavoritosWithAllFields() {
        String url = "https://sporting.com.ar/zapatillas-1";
        givenActiveFavoritoRow(url);

        var resp = controller.getFavoritos();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get(0).get("url").asText()).isEqualTo(url);
        assertThat(body.get(0).get("sitio").asText()).isEqualTo("Sporting");
        assertThat(body.get(0).get("descontinuado").asBoolean()).isFalse();
    }

    @Step("Stub DB with an active favorito row for {url}")
    private void givenActiveFavoritoRow(String url) {
        var row = Map.of(
                "url", url, "sitio", "Sporting", "nombre", "Zapatillas Nike",
                "added_at", "2025-01-01", "last_checked_at", "2025-01-15");
        when(db.listarFavoritos()).thenReturn(List.of(row));
        when(db.obtenerProducto(url)).thenReturn(Optional.<Product>empty());
        when(db.esProductoActivo(url)).thenReturn(true);
    }

    @Test
    @Story("GET /api/favoritos")
    void getFavoritosMarksDiscontinuedWhenProductInactive() {
        String url = "https://sitio.com/p/1";
        givenInactiveFavoritoRow(url);

        var resp = controller.getFavoritos();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.get(0).get("descontinuado").asBoolean()).isTrue();
    }

    @Step("Stub DB with an inactive (discontinued) favorito row for {url}")
    private void givenInactiveFavoritoRow(String url) {
        var row = Map.of("url", url, "sitio", "S", "nombre", "N",
                "added_at", "2025-01-01", "last_checked_at", "2025-01-01");
        when(db.listarFavoritos()).thenReturn(List.of(row));
        when(db.obtenerProducto(url)).thenReturn(Optional.<Product>empty());
        when(db.esProductoActivo(url)).thenReturn(false);
    }

    // ── POST /api/favoritos ──────────────────────────────────────────────

    @Test
    @Story("POST /api/favoritos")
    void addFavoritoReturns400WhenUrlBlank() {
        Allure.parameter("url", "");
        Allure.parameter("sitio", "Sporting");

        var resp = addFavorito("", "Sporting", null);
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(body.get("ok").asBoolean()).isFalse();
        verify(db, never()).guardarFavorito(any(), any(), any());
    }

    @Test
    @Story("POST /api/favoritos")
    void addFavoritoReturns400WhenSitioBlank() {
        Allure.parameter("url", "https://a.com/1");
        Allure.parameter("sitio", "");

        var resp = addFavorito("https://a.com/1", "", null);
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(body.get("ok").asBoolean()).isFalse();
    }

    @Test
    @Story("POST /api/favoritos")
    void addFavoritoReturns200AndPersistsWhenValid() {
        var resp = addFavorito("https://a.com/1", "Sporting", "Nike Air Max");
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("ok").asBoolean()).isTrue();
        verify(db).guardarFavorito("https://a.com/1", "Sporting", "Nike Air Max");
    }

    @Step("Add favorito: url={url}, sitio={sitio}, nombre={nombre}")
    private org.springframework.http.ResponseEntity<?> addFavorito(String url, String sitio, String nombre) {
        var payload = nombre == null
                ? Map.of("url", url, "sitio", sitio)
                : Map.of("url", url, "sitio", sitio, "nombre", nombre);
        return controller.addFavorito(payload);
    }

    // ── DELETE /api/favoritos ────────────────────────────────────────────

    @Test
    @Story("DELETE /api/favoritos")
    void deleteFavoritoAlwaysReturnsOkAndCallsDb() {
        var resp = deleteFavorito("https://a.com/1");
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.get("ok").asBoolean()).isTrue();
        verify(db).eliminarFavorito("https://a.com/1");
    }

    @Step("Delete favorito {url}")
    private org.springframework.http.ResponseEntity<?> deleteFavorito(String url) {
        return controller.deleteFavorito(url);
    }

    // ── POST /api/favoritos/rescrape ─────────────────────────────────────

    @Test
    @Story("POST /api/favoritos/rescrape")
    void rescrapeFavoritosReturnsIniciadoTrueWhenNotRunning() {
        givenRescrapeAvailable(true);

        var resp = controller.rescrapeFavoritos();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.get("iniciado").asBoolean()).isTrue();
    }

    @Test
    @Story("POST /api/favoritos/rescrape")
    void rescrapeFavoritosReturnsIniciadoFalseWhenAlreadyRunning() {
        givenRescrapeAvailable(false);

        var resp = controller.rescrapeFavoritos();
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.get("iniciado").asBoolean()).isFalse();
        assertThat(body.get("mensaje").asText()).contains("curso");
    }

    @Step("Stub rescrape availability: iniciado={iniciado}")
    private void givenRescrapeAvailable(boolean iniciado) {
        when(service.rescrapearFavoritos()).thenReturn(iniciado);
    }

    // ── DELETE /api/data ─────────────────────────────────────────────────

    @Test
    @Story("DELETE /api/data")
    void eliminarProductoCallsDbAndServiceAndReturnsOk() {
        String url = "https://a.com/1";

        var resp = controller.eliminarProducto(url);
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.get("ok").asBoolean()).isTrue();
        verify(db).marcarDescontinuado(url);
        verify(service).eliminarProductoDeMemoria(url);
    }
}
