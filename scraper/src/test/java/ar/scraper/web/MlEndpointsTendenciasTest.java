package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.CategoriaStats;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * close-1nf-and-3nf-foundation, Phase 3 (V16, design DD6).
 *
 * <p>{@code /api/tendencias}'s {@code distribucionCategorias.<cat>} shape MUST
 * be unchanged after {@code categoria_stats} flattens from a JSON blob to 12
 * typed columns: same 12 keys, same rounding (cv to 1 decimal, the other 11
 * integral). {@code MlEndpoints.tendencias} stops calling
 * {@code readTree(payload)} and builds the node field by field instead — this
 * pins that the two paths produce byte-identical JSON.</p>
 */
@Epic("REST API")
@Feature("Tendencias / ML Ops")
@Story("distribucionCategorias shape survives the V16 flatten")
@DisplayName("MlEndpoints.tendencias — distribucionCategorias rounding")
class MlEndpointsTendenciasTest {

    private DatabaseService db;
    private ApiController controller;

    @BeforeEach
    void setUp() {
        ScraperService service               = mock(ScraperService.class);
        InflacionService inflacionService    = mock(InflacionService.class);
        ScraperConfig config                 = mock(ScraperConfig.class);
        ResultAggregator aggregator          = mock(ResultAggregator.class);
        db                                    = mock(DatabaseService.class);
        GroupingService grouping             = mock(GroupingService.class);
        PythonRunner pythonRunner            = mock(PythonRunner.class);
        OutfitService outfitService          = mock(OutfitService.class);
        RecommendationService recommendationService = mock(RecommendationService.class);

        controller = new ApiController(service, inflacionService, config, aggregator,
                db, grouping, pythonRunner, outfitService, recommendationService);

        when(service.getLastResult()).thenReturn(mockResult());
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode ml = mapper.createObjectNode();
        ml.putObject("scores").put("https://a.com/1", 50);
        ml.putObject("tendencias").put("topCategoria", "Remera");
        when(aggregator.getLastMlOutput()).thenReturn(ml);
    }

    @Test
    @DisplayName("cv se redondea a 1 decimal, los otros 11 campos quedan enteros")
    void redondeoDeDistribucionCategoriasSePreserva() {
        CategoriaStats remera = new CategoriaStats(42, 15000, 14500, 12000, 3200, 21.3,
                11000, 18000, 7000, 2500, 500, 28500);
        when(db.cargarCategoriaStats()).thenReturn(Map.of("Remera", remera));

        JsonNode body = controller.tendencias().getBody();
        JsonNode cat = body.get("distribucionCategorias").get("Remera");

        assertThat(cat).isNotNull();
        assertThat(cat.get("n").asInt()).isEqualTo(42);
        assertThat(cat.get("mean").asLong()).isEqualTo(15000);
        assertThat(cat.get("median").asLong()).isEqualTo(14500);
        assertThat(cat.get("mode").asLong()).isEqualTo(12000);
        assertThat(cat.get("std").asLong()).isEqualTo(3200);
        assertThat(cat.get("cv").asDouble()).isEqualTo(21.3);
        assertThat(cat.get("q1").asLong()).isEqualTo(11000);
        assertThat(cat.get("q3").asLong()).isEqualTo(18000);
        assertThat(cat.get("iqr").asLong()).isEqualTo(7000);
        assertThat(cat.get("mad").asLong()).isEqualTo(2500);
        assertThat(cat.get("fence_low").asLong()).isEqualTo(500);
        assertThat(cat.get("fence_high").asLong()).isEqualTo(28500);
    }

    @Test
    @DisplayName("sin categoria_stats persistidas, distribucionCategorias no aparece")
    void sinStatsNoHayDistribucionCategorias() {
        when(db.cargarCategoriaStats()).thenReturn(Map.of());

        JsonNode body = controller.tendencias().getBody();

        assertThat(body.has("distribucionCategorias")).isFalse();
    }

    private AggregatedResult mockResult() {
        Facets facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(List.<Product>of(), Map.of(), Map.of(), facets, 0, 0);
    }
}
