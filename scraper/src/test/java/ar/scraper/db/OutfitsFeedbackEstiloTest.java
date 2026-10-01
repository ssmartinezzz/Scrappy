package ar.scraper.db;

import ar.scraper.web.support.Wire;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.web.OutfitsController;
import ar.scraper.outfits.OutfitService;
import ar.scraper.outfits.RecommendationService;
import ar.scraper.web.ScraperService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration test confirming the outfit-builder like/dislike signal is
 * SEPARATED by estilo (gym vs casual): a dislike registered while building a
 * gym outfit must not veto the same marca|categoria pair in the casual builder,
 * and vice versa. The feed's shared "catalog" signal is out of scope here (it
 * is verified by {@code RecomendadosBidirectionalTest}).
 *
 * Uses a REAL temp-file DatabaseService so writes via one endpoint are visible
 * to reads from another.
 */
@Epic("REST API")
@Feature("Outfits")
@Story("Style feedback")
@DisplayName("OutfitsController — Style feedback isolation (gym vs casual)")
class OutfitsFeedbackEstiloTest extends PostgresTestBase {

    private DatabaseService db;
    private OutfitsController controller;
    private ScraperService service;

    private Product buzo(String url, String marca, boolean gymrat) {
        return new Product("TestSitio", "Buzo " + url, 10_000, null, url, "img",
                "Buzo", "hombre", List.of(), Product.MlScore.EMPTY, marca, "indumentaria", gymrat);
    }

    @AfterEach
    void limpiarContextoDeSeguridad() {
        SujetoDePrueba.salir();
    }

    @BeforeEach
    void setUp() {
        wireController();
    }

    @Step("Wire OutfitsController with a real temp-file DatabaseService and mocked collaborators")
    private void wireController() {
        db = TestDatabaseServices.create(dataSource());

        service                                     = mock(ScraperService.class);
        RecommendationService recommendationService = new RecommendationService();
        OutfitService outfitService                 = new OutfitService(recommendationService);

        SujetoDePrueba.entrar(dataSource(), "ADMIN");

        controller = new OutfitsController(service, db.feedback(), db.outfitsGuardados(), outfitService, new ar.scraper.security.ActorResolver());
    }


    private boolean hasBuzoPumaSlot(ResponseEntity<?> resp) {
        JsonNode slots = Wire.data(resp).get("slots");
        if (slots == null) return false;
        for (JsonNode s : slots) {
            if ("Puma".equals(s.path("marca").asText()) && "Buzo".equals(s.path("categoria").asText())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void gymDislikeDoesNotLeakToCasual() {
        // Same pair "Puma|Buzo" exists as a gym item (gymrat) and a casual item (non-gymrat).
        Product gymBuzo    = buzo("https://t/gym-buzo",    "Puma", true);
        Product casualBuzo = buzo("https://t/casual-buzo", "Puma", false);
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(List.of(gymBuzo, casualBuzo));
        when(service.getLastResult()).thenReturn(result);

        // Dislike the pair while in the GYM builder.
        controller.outfitFeedback(Map.of(
                "genero", "hombre",
                "estilo", "gym",
                "items", List.of(Map.of("slot", "torso-outer", "url", "https://t/gym-buzo", "liked", false))
        ));

        // Gym builder: pair vetoed → the gym Buzo is excluded (only candidate) → absent.
        Allure.parameter("estilo", "gym");
        ResponseEntity<?> gymResp = controller.outfitsBuilder(
                "Buzo", 500_000, "hombre", "", "", false, "gym");
        assertThat(hasBuzoPumaSlot(gymResp)).isFalse();

        // Casual builder: gym dislike must NOT leak → the casual Buzo is present.
        Allure.parameter("estilo", "casual");
        ResponseEntity<?> casualResp = controller.outfitsBuilder(
                "Buzo", 500_000, "hombre", "", "", false, "casual");
        assertThat(hasBuzoPumaSlot(casualResp)).isTrue();
    }

    @Test
    void resetGymDoesNotClearCasual() {
        Product casualBuzo = buzo("https://t/casual-buzo", "Puma", false);
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(List.of(casualBuzo));
        when(service.getLastResult()).thenReturn(result);

        // Dislike the pair in the CASUAL builder.
        controller.outfitFeedback(Map.of(
                "genero", "hombre",
                "estilo", "casual",
                "items", List.of(Map.of("slot", "torso-outer", "url", "https://t/casual-buzo", "liked", false))
        ));

        // Reset only the GYM history — casual veto must survive.
        Allure.parameter("estilo", "gym");
        controller.resetOutfitFeedback("gym");

        ResponseEntity<?> casualResp = controller.outfitsBuilder(
                "Buzo", 500_000, "hombre", "", "", false, "casual");
        assertThat(hasBuzoPumaSlot(casualResp)).isFalse();

        // Reset casual → veto gone → pair reappears.
        controller.resetOutfitFeedback("casual");
        ResponseEntity<?> afterReset = controller.outfitsBuilder(
                "Buzo", 500_000, "hombre", "", "", false, "casual");
        assertThat(hasBuzoPumaSlot(afterReset)).isTrue();
    }
}
