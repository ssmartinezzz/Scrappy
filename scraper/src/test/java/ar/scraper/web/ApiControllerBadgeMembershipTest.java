package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import ar.scraper.model.Product.SenalFinanciacion;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * badges-oportunidades-revamp T3.4/T3.6: {@code /api/data?badge=} must
 * filter by SET MEMBERSHIP (a product matches if the requested badge is
 * anywhere in its badge set, not only when it's the principal badge), and
 * {@code /api/facets} must count a product once PER badge it holds.
 * Mirrors {@code ApiControllerPageClampTest}'s convention: {@code
 * ApiController} is a plain {@code @RestController} POJO instantiated
 * directly with Mockito-mocked collaborators.
 */
@Epic("REST API")
@Feature("Filtros / Facets")
@Story("Multi-badge membership")
@DisplayName("ApiController — badge set membership filter + per-badge facet counts")
class ApiControllerBadgeMembershipTest extends ar.scraper.db.support.PostgresTestBase {

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
        service          = mock(ScraperService.class);
        inflacionService = mock(InflacionService.class);
        config            = mock(ScraperConfig.class);
        aggregator        = mock(ResultAggregator.class);
        db                = new DatabaseService(dataSource());
        grouping          = mock(GroupingService.class);
        pythonRunner      = mock(PythonRunner.class);
        outfitService     = mock(OutfitService.class);
        recommendationService = mock(RecommendationService.class);
        controller = new ApiController(service, inflacionService, config, aggregator,
                db, grouping, pythonRunner, outfitService, recommendationService);

        when(config.getMoneda()).thenReturn("ARS");
    }

    private Product productoConBadges(String url, double precio, List<String> badges) {
        Product.MlScore ml = new Product.MlScore(
                60, badges, false, "estable", 50, 0.0, "standard");
        return new Product("Sitio", "Producto " + url, precio, null, url, "img",
                "Remera", "unisex", List.of(), ml, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY);
    }

    private final java.util.List<Product> sembrados = new java.util.ArrayList<>();

    /**
     * `/api/data` lee de la BASE desde `sql-catalog-filtering`. Acumulativo
     * porque upsertProductos hace soft-delete de lo que no viene en el batch.
     */
    @Step("Seed the catalog with the given products")
    private void resultFor(Product... productos) {
        sembrados.addAll(List.of(productos));
        db.upsertProductos(List.copyOf(sembrados));
    }

    @Test
    void dataFilterMatchesProductWhenBadgeIsSecondaryNotPrincipal() {
        Product a = productoConBadges("https://site.com/a", 1000, List.of("trending", "below_market"));
        Product b = productoConBadges("https://site.com/b", 2000, List.of("above_market"));
        resultFor(a, b);

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                "trending", null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(1);
        assertThat(productos.get(0).path("url").asText()).isEqualTo("https://site.com/a");
    }

    @Test
    void dataFilterMatchesProductWhenBadgeIsPrincipal() {
        Product a = productoConBadges("https://site.com/c", 1000, List.of("all_time_low"));
        Product b = productoConBadges("https://site.com/d", 2000, List.of("above_market"));
        resultFor(a, b);

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                "all_time_low", null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);

        JsonNode productos = ((JsonNode) resp.getBody()).path("productos");
        assertThat(productos).hasSize(1);
        assertThat(productos.get(0).path("url").asText()).isEqualTo("https://site.com/c");
    }

    @Test
    void dataFilterPaginationConsistentAcrossPagesForBadgeMembership() {
        Product a = productoConBadges("https://site.com/e", 1000, List.of("trending"));
        Product b = productoConBadges("https://site.com/f", 2000, List.of("trending", "verified_deal"));
        Product c = productoConBadges("https://site.com/g", 3000, List.of("above_market"));
        resultFor(a, b, c);

        ResponseEntity<?> page1 = controller.data(1, 1, null, null, null, null, null, null,
                "trending", null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);
        ResponseEntity<?> page2 = controller.data(2, 1, null, null, null, null, null, null,
                "trending", null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);

        assertThat(((JsonNode) page1.getBody()).path("meta").path("total").asInt()).isEqualTo(2);
        assertThat(((JsonNode) page1.getBody()).path("productos")).hasSize(1);
        assertThat(((JsonNode) page2.getBody()).path("productos")).hasSize(1);
        assertThat(((JsonNode) page1.getBody()).path("productos").get(0).path("url").asText())
                .isNotEqualTo(((JsonNode) page2.getBody()).path("productos").get(0).path("url").asText());
    }

    @Test
    void facetsCountsProductOncePerBadgeItHolds() {
        Product a = productoConBadges("https://site.com/h", 1000, List.of("trending", "verified_deal"));
        Product b = productoConBadges("https://site.com/i", 2000, List.of("trending"));
        resultFor(a, b);

        ResponseEntity<?> resp = controller.facets();

        JsonNode badges = ((JsonNode) resp.getBody()).path("badges");
        assertThat(badges.path("trending").asInt()).isEqualTo(2);
        assertThat(badges.path("verified_deal").asInt()).isEqualTo(1);
    }

    @Test
    void dataResponseIncludesFullBadgesArrayForCard() {
        Product a = productoConBadges("https://site.com/j", 1000, List.of("verified_deal", "trending"));
        resultFor(a);

        ResponseEntity<?> resp = controller.data(1, 24, null, null, null, null, null, null,
                null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);

        JsonNode ml = ((JsonNode) resp.getBody()).path("productos").get(0).path("ml");
        assertThat(ml.path("badge").asText()).isEqualTo("verified_deal");
        List<String> badgesArr = new java.util.ArrayList<>();
        ml.path("badges").forEach(n -> badgesArr.add(n.asText()));
        assertThat(badgesArr).containsExactly("verified_deal", "trending");
    }
}
