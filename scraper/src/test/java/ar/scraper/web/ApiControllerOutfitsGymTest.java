package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Epic("REST API")
@Feature("Outfits")
@Story("Gym outfits")
@DisplayName("ApiController — Gym outfits endpoint")
class ApiControllerOutfitsGymTest {

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

    // ── GET /api/outfits ─────────────────────────────────────────────────

    @Test
    void outfitsReturns204WhenNoLastResult() {
        when(service.getLastResult()).thenReturn(null);

        var resp = controller.outfits("hombre", 0, "", 0);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verifyNoInteractions(outfitService);
    }

    @Test
    void outfitsReturns200WithSlotArrayWhenResultExists() {
        when(service.getLastResult()).thenReturn(mockResult(List.of()));
        when(db.obtenerOutfitFeedback()).thenReturn(List.of());
        when(db.obtenerCategoriaDismiss()).thenReturn(Set.of());

        var emptyOutfit = new OutfitService.Outfit(List.of(), "hombre", false, 0.0, false);
        when(outfitService.armar(any(), any(), eq("gym"), any(), anyDouble(), any()))
                .thenReturn(emptyOutfit);
        when(outfitService.armarComboSuplementos(any(), anyDouble())).thenReturn(List.of());

        var resp = controller.outfits("hombre", 0, "", 0);
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("genero").asText()).isEqualTo("hombre");
        assertThat(body.get("slots").isArray()).isTrue();
        assertThat(body.get("suplementos").isArray()).isTrue();
    }

    @Test
    void outfitsIncludesSlotsWhenOutfitHasProducts() {
        when(service.getLastResult()).thenReturn(mockResult(List.of()));
        when(db.obtenerOutfitFeedback()).thenReturn(List.of());
        when(db.obtenerCategoriaDismiss()).thenReturn(Set.of());

        var pick = new OutfitService.SlotPick(
                "Remera", "Sporting", "Nike Tee", 8000.0,
                "https://a.com/1", "img.jpg", "Remeras", "Nike");
        var outfit = new OutfitService.Outfit(List.of(pick), "hombre", false, 8000.0, false);
        when(outfitService.armar(any(), any(), eq("gym"), any(), anyDouble(), any()))
                .thenReturn(outfit);
        when(outfitService.armarComboSuplementos(any(), anyDouble())).thenReturn(List.of());

        var resp = controller.outfits("hombre", 10000, "", 0);
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(body.get("totalEstimado").asDouble()).isEqualTo(8000.0);
        assertThat(body.get("slots").size()).isEqualTo(1);
        assertThat(body.get("slots").get(0).get("slot").asText()).isEqualTo("Remera");
    }

    @Test
    void outfitsPassesExcluirUrlsToService() {
        when(service.getLastResult()).thenReturn(mockResult(List.of()));
        when(db.obtenerOutfitFeedback()).thenReturn(List.of());
        when(db.obtenerCategoriaDismiss()).thenReturn(Set.of());

        var emptyOutfit = new OutfitService.Outfit(List.of(), "hombre", false, 0.0, false);
        when(outfitService.armar(any(), any(), eq("gym"), any(), anyDouble(), any()))
                .thenReturn(emptyOutfit);
        when(outfitService.armarComboSuplementos(any(), anyDouble())).thenReturn(List.of());

        Allure.parameter("excluir", "https://a.com/1,https://b.com/2");
        controller.outfits("hombre", 0, "https://a.com/1,https://b.com/2", 0);

        verify(outfitService).armar(any(), any(), eq("gym"), any(), anyDouble(),
                eq(Set.of("https://a.com/1", "https://b.com/2")));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private AggregatedResult mockResult(List<Product> products) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(products, Map.of(), Map.of(), facets, 0, 0);
    }
}
