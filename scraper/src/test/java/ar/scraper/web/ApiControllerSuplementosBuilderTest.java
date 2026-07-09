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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Epic("REST API")
@Feature("Suplementos")
@DisplayName("ApiController — Suplementos builder endpoint")
class ApiControllerSuplementosBuilderTest {

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

    @Test
    void suplementosBuilder_returns204WhenNoCatalog() {
        when(service.getLastResult()).thenReturn(null);

        var resp = controller.suplementosBuilder("Proteína", 0);

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verifyNoInteractions(outfitService);
    }

    @Test
    void suplementosBuilder_returns400WhenTiposIsBlank() {
        Allure.parameter("tipos", "");
        var resp = controller.suplementosBuilder("", 0);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(outfitService);
    }

    @Test
    void suplementosBuilder_returns400WhenTiposIsNull() {
        var resp = controller.suplementosBuilder(null, 0);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(outfitService);
    }

    @Test
    void suplementosBuilder_returns200WithEmptyListWhenNoMatches() {
        when(service.getLastResult()).thenReturn(mockResult(List.of()));
        when(outfitService.armarComboSuplementos(any(), anyDouble(), any())).thenReturn(List.of());

        var resp = controller.suplementosBuilder("Proteína", 0);
        JsonNode body = AllureSteps.toJson(resp.getBody());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("picks").isArray()).isTrue();
        assertThat(body.get("picks").size()).isEqualTo(0);
        assertThat(body.get("sinStock").isArray()).isTrue();
    }

    @Test
    void suplementosBuilder_returnsOnePickPerRequestedType() {
        when(service.getLastResult()).thenReturn(mockResult(List.of()));
        var pick1 = new OutfitService.SupplementPick(
                "Proteína en Polvo", "Sitio1", "Whey 1kg", 5000.0, "https://a.com/p1", "img1.jpg", "ENA");
        var pick2 = new OutfitService.SupplementPick(
                "Creatina", "Sitio2", "Creatina 300g", 3000.0, "https://a.com/p2", "img2.jpg", "STAR");
        when(outfitService.armarComboSuplementos(any(), anyDouble(), any()))
                .thenReturn(List.of(pick1, pick2));

        var resp = controller.suplementosBuilder("Proteína en Polvo,Creatina", 0);
        JsonNode picks = AllureSteps.toJson(resp.getBody()).get("picks");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(picks.size()).isEqualTo(2);
        assertThat(picks.get(0).get("tipo").asText()).isEqualTo("Proteína en Polvo");
        assertThat(picks.get(0).get("nombre").asText()).isEqualTo("Whey 1kg");
        assertThat(picks.get(0).get("precio").asDouble()).isEqualTo(5000.0);
        assertThat(picks.get(0).get("url").asText()).isEqualTo("https://a.com/p1");
        assertThat(picks.get(1).get("tipo").asText()).isEqualTo("Creatina");
    }

    @Test
    void suplementosBuilder_passesPresupuestoToService() {
        when(service.getLastResult()).thenReturn(mockResult(List.of()));
        when(outfitService.armarComboSuplementos(any(), anyDouble(), any())).thenReturn(List.of());

        Allure.parameter("presupuesto", 50000);
        controller.suplementosBuilder("Proteína", 50000);

        verify(outfitService).armarComboSuplementos(any(), eq(50000.0), any());
    }

    @Test
    void suplementosBuilder_passesOnlyRequestedTiposToService() {
        when(service.getLastResult()).thenReturn(mockResult(List.of()));
        when(outfitService.armarComboSuplementos(any(), anyDouble(), any())).thenReturn(List.of());

        controller.suplementosBuilder("Proteína,Creatina", 0);

        verify(outfitService).armarComboSuplementos(any(), anyDouble(), eq(Set.of("Proteína", "Creatina")));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private AggregatedResult mockResult(List<Product> products) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(products, Map.of(), Map.of(), facets, 0, 0);
    }
}
