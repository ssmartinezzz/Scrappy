package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApiControllerSavedOutfitsTest {

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

    // ── POST /api/outfits/save ─────────────────────────────────────────────

    @Test
    void saveOutfitValidPayloadPersistsAndReturnsIdAndOk() {
        when(db.guardarOutfit(eq("Test Outfit"), anyString(), any(), eq(50000.0))).thenReturn(1);

        ResponseEntity<?> resp = controller.saveOutfit(
                Map.of("nombre", "Test Outfit", "slots", List.of(), "totalEstimado", 50000.0));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isTrue();
        assertThat(body.path("id").asInt()).isEqualTo(1);
        assertThat(body.path("nombre").asText()).isEqualTo("Test Outfit");
    }

    @Test
    void saveOutfitDbFailureReturns500WithOkFalse() {
        when(db.guardarOutfit(any(), anyString(), any(), anyDouble())).thenReturn(-1);

        ResponseEntity<?> resp = controller.saveOutfit(
                Map.of("nombre", "x", "slots", List.of(), "totalEstimado", 1000.0));

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
    }

    @Test
    void saveOutfitBlankNombreDefaultsToOutfit() {
        // blank nombre is trimmed; controller does not 400 on empty name
        when(db.guardarOutfit(anyString(), anyString(), any(), eq(0.0))).thenReturn(1);

        ResponseEntity<?> resp = controller.saveOutfit(
                Map.of("nombre", "  ", "slots", List.of(), "totalEstimado", 0));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isTrue();
        verify(db).guardarOutfit(anyString(), anyString(), any(), eq(0.0));
    }

    // ── GET /api/outfits/saved ─────────────────────────────────────────────

    @Test
    void getSavedOutfitsReturnsListFromDb() {
        Map<String, Object> outfit1 = Map.of("id", 1, "nombre", "Outfit 1", "totalEstimado", 100.0);
        Map<String, Object> outfit2 = Map.of("id", 2, "nombre", "Outfit 2", "totalEstimado", 200.0);
        when(db.obtenerOutfitsGuardados()).thenReturn(List.of(outfit1, outfit2));

        ResponseEntity<?> resp = controller.getSavedOutfits();

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        List<?> body = (List<?>) resp.getBody();
        assertThat(body).hasSize(2);
    }

    // ── DELETE /api/outfits/saved/{id} ─────────────────────────────────────

    @Test
    void deleteSavedOutfitFoundReturnsOkTrue() {
        when(db.eliminarOutfitGuardado(3)).thenReturn(true);

        ResponseEntity<?> resp = controller.deleteSavedOutfit(3);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isTrue();
        assertThat(body.path("mensaje").asText()).contains("eliminado");
    }

    @Test
    void deleteSavedOutfitNotFoundReturns404WithOkFalse() {
        when(db.eliminarOutfitGuardado(999)).thenReturn(false);

        ResponseEntity<?> resp = controller.deleteSavedOutfit(999);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
    }

    // ── PATCH /api/outfits/saved/{id}/nombre ──────────────────────────────

    @Test
    void renameSavedOutfitValidPayloadUpdatesAndReturnsOk() {
        when(db.renombrarOutfit(5, "Mi Outfit")).thenReturn(true);

        ResponseEntity<?> resp = controller.renameSavedOutfit(5, Map.of("nombre", "Mi Outfit"));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isTrue();
        verify(db).renombrarOutfit(5, "Mi Outfit");
    }

    @Test
    void renameSavedOutfitBlankNombreReturns400() {
        ResponseEntity<?> resp = controller.renameSavedOutfit(5, Map.of("nombre", "   "));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
        verify(db, never()).renombrarOutfit(anyInt(), anyString());
    }

    @Test
    void renameSavedOutfitNotFoundReturns404() {
        when(db.renombrarOutfit(99, "x")).thenReturn(false);

        ResponseEntity<?> resp = controller.renameSavedOutfit(99, Map.of("nombre", "x"));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
    }
}
