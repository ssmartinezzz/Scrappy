package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

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
    void getFavoritosReturnsEmptyArrayWhenNoFavorites() {
        when(db.listarFavoritos()).thenReturn(List.of());

        var resp = controller.getFavoritos();
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.isArray()).isTrue();
        assertThat(body.size()).isEqualTo(0);
    }

    @Test
    void getFavoritosReturnsFavoritosWithAllFields() {
        String url = "https://sporting.com.ar/zapatillas-1";
        var row = Map.of(
                "url", url, "sitio", "Sporting", "nombre", "Zapatillas Nike",
                "added_at", "2025-01-01", "last_checked_at", "2025-01-15");
        when(db.listarFavoritos()).thenReturn(List.of(row));
        when(db.obtenerProducto(url)).thenReturn(Optional.empty());
        when(db.esProductoActivo(url)).thenReturn(true);

        var resp = controller.getFavoritos();
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.size()).isEqualTo(1);
        assertThat(body.get(0).get("url").asText()).isEqualTo(url);
        assertThat(body.get(0).get("sitio").asText()).isEqualTo("Sporting");
        assertThat(body.get(0).get("descontinuado").asBoolean()).isFalse();
    }

    @Test
    void getFavoritosMarksDiscontinuedWhenProductInactive() {
        String url = "https://sitio.com/p/1";
        var row = Map.of("url", url, "sitio", "S", "nombre", "N",
                "added_at", "2025-01-01", "last_checked_at", "2025-01-01");
        when(db.listarFavoritos()).thenReturn(List.of(row));
        when(db.obtenerProducto(url)).thenReturn(Optional.empty());
        when(db.esProductoActivo(url)).thenReturn(false);

        var resp = controller.getFavoritos();
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get(0).get("descontinuado").asBoolean()).isTrue();
    }

    // ── POST /api/favoritos ──────────────────────────────────────────────

    @Test
    void addFavoritoReturns400WhenUrlBlank() {
        var resp = controller.addFavorito(Map.of("url", "", "sitio", "Sporting"));
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(body.get("ok").asBoolean()).isFalse();
        verify(db, never()).guardarFavorito(any(), any(), any());
    }

    @Test
    void addFavoritoReturns400WhenSitioBlank() {
        var resp = controller.addFavorito(Map.of("url", "https://a.com/1", "sitio", ""));
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(body.get("ok").asBoolean()).isFalse();
    }

    @Test
    void addFavoritoReturns200AndPersistsWhenValid() {
        var resp = controller.addFavorito(Map.of(
                "url", "https://a.com/1", "sitio", "Sporting", "nombre", "Nike Air Max"));
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("ok").asBoolean()).isTrue();
        verify(db).guardarFavorito("https://a.com/1", "Sporting", "Nike Air Max");
    }

    // ── DELETE /api/favoritos ────────────────────────────────────────────

    @Test
    void deleteFavoritoAlwaysReturnsOkAndCallsDb() {
        var resp = controller.deleteFavorito("https://a.com/1");
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("ok").asBoolean()).isTrue();
        verify(db).eliminarFavorito("https://a.com/1");
    }

    // ── POST /api/favoritos/rescrape ─────────────────────────────────────

    @Test
    void rescrapeFavoritosReturnsIniciadoTrueWhenNotRunning() {
        when(service.rescrapearFavoritos()).thenReturn(true);

        var resp = controller.rescrapeFavoritos();
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("iniciado").asBoolean()).isTrue();
    }

    @Test
    void rescrapeFavoritosReturnsIniciadoFalseWhenAlreadyRunning() {
        when(service.rescrapearFavoritos()).thenReturn(false);

        var resp = controller.rescrapeFavoritos();
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("iniciado").asBoolean()).isFalse();
        assertThat(body.get("mensaje").asText()).contains("curso");
    }

    // ── DELETE /api/data ─────────────────────────────────────────────────

    @Test
    void eliminarProductoCallsDbAndServiceAndReturnsOk() {
        String url = "https://a.com/1";

        var resp = controller.eliminarProducto(url);
        JsonNode body = new ObjectMapper().valueToTree(resp.getBody());

        assertThat(body.get("ok").asBoolean()).isTrue();
        verify(db).marcarDescontinuado(url);
        verify(service).eliminarProductoDeMemoria(url);
    }
}
