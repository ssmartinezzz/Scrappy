package ar.scraper.web;

import ar.scraper.web.support.Wire;
import ar.scraper.outfits.OutfitService;


import ar.scraper.db.DatabaseService;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import ar.scraper.web.support.SujetoDePrueba;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Epic("REST API")
@Feature("Outfits")
@Story("Saved outfits")
@DisplayName("OutfitsController — Saved outfits endpoints")
class OutfitsSavedTest {

    private ScraperService service;
    private DatabaseService db;
    private ar.scraper.feedback.FeedbackPort feedback;
    private ar.scraper.outfits.SavedOutfitsPort outfitsGuardados;
    private OutfitService outfitService;
    private OutfitsController controller;

    @AfterEach
    void limpiarContexto() {
        SujetoDePrueba.salir();
    }

    @BeforeEach
    void setUp() {
        wireController();
    }

    @Step("Wire OutfitsController with mocked collaborators")
    private void wireController() {
        service               = mock(ScraperService.class);
        db                    = mock(DatabaseService.class);
        feedback              = mock(ar.scraper.feedback.FeedbackPort.class);
        when(db.feedback()).thenReturn(feedback);
        outfitsGuardados      = mock(ar.scraper.outfits.SavedOutfitsPort.class);
        when(db.outfitsGuardados()).thenReturn(outfitsGuardados);
        outfitService         = mock(OutfitService.class);
        SujetoDePrueba.entrar("ADMIN");
        controller = new OutfitsController(service, db.feedback(), db.outfitsGuardados(), outfitService, new ar.scraper.security.ActorResolver());
    }

    // ── POST /api/outfits/save ─────────────────────────────────────────────

    @Test
    void saveOutfitValidPayloadPersistsAndReturnsIdAndOk() {
        when(outfitsGuardados.guardarOutfit(any(), eq("Test Outfit"), anyString(), any(), eq(50000.0))).thenReturn(1);

        ResponseEntity<?> resp = controller.saveOutfit(
                Map.of("nombre", "Test Outfit", "slots", List.of(), "totalEstimado", 50000.0));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = Wire.data(resp);
        assertThat(body.path("ok").asBoolean()).isTrue();
        assertThat(body.path("id").asInt()).isEqualTo(1);
        assertThat(body.path("nombre").asText()).isEqualTo("Test Outfit");
    }

    @Test
    void saveOutfitDbFailureReturns500WithOkFalse() {
        when(outfitsGuardados.guardarOutfit(any(), any(), anyString(), any(), anyDouble())).thenReturn(-1);

        ResponseEntity<?> resp = Wire.answer(() -> controller.saveOutfit(
                Map.of("nombre", "x", "slots", List.of(), "totalEstimado", 1000.0)));

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        JsonNode body = Wire.data(resp);
        assertThat(body.path("ok").asBoolean()).isFalse();
    }

    @Test
    void saveOutfitBlankNombreDefaultsToOutfit() {
        // blank nombre is trimmed; controller does not 400 on empty name
        when(outfitsGuardados.guardarOutfit(any(), anyString(), anyString(), any(), eq(0.0))).thenReturn(1);

        ResponseEntity<?> resp = controller.saveOutfit(
                Map.of("nombre", "  ", "slots", List.of(), "totalEstimado", 0));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = Wire.data(resp);
        assertThat(body.path("ok").asBoolean()).isTrue();
        verify(outfitsGuardados).guardarOutfit(any(), anyString(), anyString(), any(), eq(0.0));
    }

    // ── GET /api/outfits/saved ─────────────────────────────────────────────

    @Test
    void getSavedOutfitsReturnsListFromDb() {
        Map<String, Object> outfit1 = Map.of("id", 1, "nombre", "Outfit 1", "totalEstimado", 100.0);
        Map<String, Object> outfit2 = Map.of("id", 2, "nombre", "Outfit 2", "totalEstimado", 200.0);
        when(outfitsGuardados.obtenerOutfitsGuardados(any())).thenReturn(List.of(outfit1, outfit2));

        ResponseEntity<?> resp = controller.getSavedOutfits();

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(Wire.data(resp)).hasSize(2);
    }

    // ── DELETE /api/outfits/saved/{id} ─────────────────────────────────────

    @Test
    void deleteSavedOutfitFoundReturnsOkTrue() {
        when(outfitsGuardados.eliminarOutfitGuardado(any(), eq(3))).thenReturn(true);

        ResponseEntity<?> resp = controller.deleteSavedOutfit(3);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = Wire.data(resp);
        assertThat(body.path("ok").asBoolean()).isTrue();
        assertThat(body.path("mensaje").asText()).contains("eliminado");
    }

    @Test
    void deleteSavedOutfitNotFoundReturns404WithOkFalse() {
        when(outfitsGuardados.eliminarOutfitGuardado(any(), eq(999))).thenReturn(false);

        Allure.parameter("id", 999);
        ResponseEntity<?> resp = Wire.answer(() -> controller.deleteSavedOutfit(999));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        JsonNode body = Wire.data(resp);
        assertThat(body.path("ok").asBoolean()).isFalse();
    }

    // ── PATCH /api/outfits/saved/{id}/nombre ──────────────────────────────

    @Test
    void renameSavedOutfitValidPayloadUpdatesAndReturnsOk() {
        when(outfitsGuardados.renombrarOutfit(any(), eq(5), eq("Mi Outfit"))).thenReturn(true);

        ResponseEntity<?> resp = controller.renameSavedOutfit(5, Map.of("nombre", "Mi Outfit"));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = Wire.data(resp);
        assertThat(body.path("ok").asBoolean()).isTrue();
        verify(outfitsGuardados).renombrarOutfit(any(), eq(5), eq("Mi Outfit"));
    }

    @Test
    void renameSavedOutfitBlankNombreReturns400() {
        ResponseEntity<?> resp = Wire.answer(() -> controller.renameSavedOutfit(5, Map.of("nombre", "   ")));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode body = Wire.data(resp);
        assertThat(body.path("ok").asBoolean()).isFalse();
        verify(outfitsGuardados, never()).renombrarOutfit(any(), anyInt(), anyString());
    }

    @Test
    void renameSavedOutfitNotFoundReturns404() {
        when(outfitsGuardados.renombrarOutfit(any(), eq(99), eq("x"))).thenReturn(false);

        Allure.parameter("id", 99);
        ResponseEntity<?> resp = Wire.answer(() -> controller.renameSavedOutfit(99, Map.of("nombre", "x")));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        JsonNode body = Wire.data(resp);
        assertThat(body.path("ok").asBoolean()).isFalse();
    }
}
