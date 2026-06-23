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
 * Integration test confirming the bidirectional shared taste signal between
 * the outfit-builder and the new recommendations feed (spec.md "Shared
 * Taste Signal Across Surfaces"). Uses a REAL temp-file {@link DatabaseService}
 * (not mocked) so that writes via one endpoint are actually visible to reads
 * from the other endpoint — mirrors {@code DatabaseServicePresetTest}'s real
 * SQLite seam, applied at the {@code ApiController} level.
 */
class ApiControllerRecomendadosBidirectionalTest {

    @TempDir
    Path tempDir;

    private DatabaseService db;
    private ApiController controller;
    private ScraperService service;
    private ResultAggregator aggregator;

    private Product producto(String url, String marca, String categoria) {
        return new Product("TestSitio", "Producto " + url, 10000, null, url, "img",
                categoria, "hombre", List.of(), Product.MlScore.EMPTY, marca, "indumentaria", true);
    }

    @BeforeEach
    void setUp() {
        db = new DatabaseService();
        db.initEn(tempDir.resolve("test-bidirectional.db").toString());

        service           = mock(ScraperService.class);
        InflacionService inflacionService = mock(InflacionService.class);
        ScraperConfig config              = mock(ScraperConfig.class);
        aggregator                        = mock(ResultAggregator.class);
        GroupingService grouping          = mock(GroupingService.class);
        PythonRunner pythonRunner         = mock(PythonRunner.class);
        OutfitService outfitService       = new OutfitService();
        RecommendationService recommendationService = new RecommendationService();

        when(config.getMoneda()).thenReturn("ARS");

        controller = new ApiController(service, inflacionService, config, aggregator,
                db, grouping, pythonRunner, outfitService, recommendationService);
    }

    @AfterEach
    void tearDown() {
        db.cerrar();
    }

    @Test
    void dislikeViaRecomendadosFeedbackExcludesPairFromOutfits() {
        Product puma = producto("https://t/puma-buzo", "Puma", "Buzo");
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(List.of(puma));
        when(service.getLastResult()).thenReturn(result);

        controller.recomendadosFeedback(Map.of(
                "genero", "hombre",
                "items", List.of(Map.of("url", "https://t/puma-buzo", "liked", false))
        ));

        ResponseEntity<com.fasterxml.jackson.databind.node.ObjectNode> outfitsResp =
                controller.outfits("hombre");

        JsonNode slots = outfitsResp.getBody().get("slots");
        boolean pumaBuzoPresent = false;
        for (JsonNode slot : slots) {
            if ("Puma".equals(slot.get("marca").asText()) && "Buzo".equals(slot.get("categoria").asText())) {
                pumaBuzoPresent = true;
            }
        }
        assertThat(pumaBuzoPresent).isFalse();
    }

    @Test
    void likeViaOutfitsFeedbackBoostsPairInRecomendados() {
        Product nikeZapatilla = producto("https://t/nike-zap", "Nike", "Zapatilla");
        Product otherZapatilla = producto("https://t/other-zap", "Asics", "Zapatilla");
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(List.of(nikeZapatilla, otherZapatilla));
        when(service.getLastResult()).thenReturn(result);

        controller.outfitFeedback(Map.of(
                "genero", "hombre",
                "items", List.of(Map.of("slot", "calzado", "url", "https://t/nike-zap", "liked", true))
        ));

        ResponseEntity<com.fasterxml.jackson.databind.node.ObjectNode> recoResp =
                controller.recomendados(1, 24, null, null);

        JsonNode items = recoResp.getBody().get("items");
        // Nike|Zapatilla boosted -> must rank first (equal base ML score otherwise).
        assertThat(items.get(0).get("marca").asText()).isEqualTo("Nike");
    }
}
