package ar.scraper.db;

import ar.scraper.aggregator.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import ar.scraper.web.ApiController;
import ar.scraper.web.InflacionService;
import ar.scraper.web.OutfitService;
import ar.scraper.web.RecommendationService;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
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
 * is verified by {@code ApiControllerRecomendadosBidirectionalTest}).
 *
 * Uses a REAL temp-file DatabaseService so writes via one endpoint are visible
 * to reads from another.
 */
class ApiControllerFeedbackEstiloTest {

    @TempDir
    Path tempDir;

    private DatabaseService db;
    private ApiController controller;
    private ScraperService service;

    private Product buzo(String url, String marca, boolean gymrat) {
        return new Product("TestSitio", "Buzo " + url, 10_000, null, url, "img",
                "Buzo", "hombre", List.of(), Product.MlScore.EMPTY, marca, "indumentaria", gymrat);
    }

    @BeforeEach
    void setUp() {
        db = new DatabaseService();
        db.initEn(tempDir.resolve("test-feedback-estilo.db").toString());

        service                                     = mock(ScraperService.class);
        InflacionService inflacionService           = mock(InflacionService.class);
        ScraperConfig config                        = mock(ScraperConfig.class);
        ResultAggregator aggregator                 = mock(ResultAggregator.class);
        GroupingService grouping                    = mock(GroupingService.class);
        PythonRunner pythonRunner                   = mock(PythonRunner.class);
        RecommendationService recommendationService = new RecommendationService();
        OutfitService outfitService                 = new OutfitService(recommendationService);

        controller = new ApiController(service, inflacionService, config, aggregator,
                db, grouping, pythonRunner, outfitService, recommendationService);
    }

    @AfterEach
    void tearDown() {
        db.cerrar();
    }

    private boolean hasBuzoPumaSlot(ResponseEntity<ObjectNode> resp) {
        JsonNode slots = resp.getBody().get("slots");
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
        ResponseEntity<ObjectNode> gymResp = controller.outfitsBuilder(
                "Buzo", 500_000, "hombre", "", "", false, "gym");
        assertThat(hasBuzoPumaSlot(gymResp)).isFalse();

        // Casual builder: gym dislike must NOT leak → the casual Buzo is present.
        ResponseEntity<ObjectNode> casualResp = controller.outfitsBuilder(
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
        controller.resetOutfitFeedback("gym");

        ResponseEntity<ObjectNode> casualResp = controller.outfitsBuilder(
                "Buzo", 500_000, "hombre", "", "", false, "casual");
        assertThat(hasBuzoPumaSlot(casualResp)).isFalse();

        // Reset casual → veto gone → pair reappears.
        controller.resetOutfitFeedback("casual");
        ResponseEntity<ObjectNode> afterReset = controller.outfitsBuilder(
                "Buzo", 500_000, "hombre", "", "", false, "casual");
        assertThat(hasBuzoPumaSlot(afterReset)).isTrue();
    }
}
