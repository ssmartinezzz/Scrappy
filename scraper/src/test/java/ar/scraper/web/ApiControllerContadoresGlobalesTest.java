package ar.scraper.web;

import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The three catalog-wide counters {@code GET /api/data} and {@code GET /api/facets}
 * publish alongside the precomputed facets: the {@code rubros} histogram,
 * {@code gymratCount} and {@code packCount}.
 *
 * <p>Only {@code packCount} had any coverage, which made these unsafe to touch —
 * they were computed with three separate stream passes over the full catalog on
 * every single request, and nothing pinned their semantics. This class pins them
 * before that is collapsed into one pass.</p>
 *
 * <p>The load-bearing property is {@link
 * #losContadoresSonDelCatalogoCompletoNoDeLaPaginaFiltrada()}: these counters
 * deliberately ignore the active filters, because the UI uses them to decide
 * whether to even offer a filter pill. Computing them over the filtered result
 * would make pills vanish as soon as you used them.</p>
 */
@Epic("REST API")
@Feature("Filtros / Facets")
@Story("Contadores globales del catálogo")
@DisplayName("ApiController — rubros / gymratCount / packCount")
class ApiControllerContadoresGlobalesTest {

    private ScraperService  service;
    private ScraperConfig   config;
    private DatabaseService db;
    private ApiController   controller;

    @BeforeEach
    void setUp() {
        wireController();
        when(config.getMoneda()).thenReturn("ARS");
        when(db.cargarPresetActivo()).thenReturn(Optional.empty());
    }

    @Step("Wire ApiController with mocked collaborators")
    private void wireController() {
        service = mock(ScraperService.class);
        config  = mock(ScraperConfig.class);
        db      = mock(DatabaseService.class);
        controller = new ApiController(service, mock(InflacionService.class), config,
                mock(ResultAggregator.class), db, mock(GroupingService.class),
                mock(PythonRunner.class), mock(OutfitService.class),
                mock(RecommendationService.class));
    }

    /** @param unidades >1 makes it a pack. */
    private Product producto(String url, double precio, String rubro, boolean gymrat, int unidades) {
        return new Product("Sitio", "Producto " + url, precio, null, url, "img",
                "Remeras", "unisex", List.of("M"), Product.MlScore.EMPTY, "Marca",
                rubro, gymrat, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, unidades, "", Product.VisualAttrs.EMPTY);
    }

    private void publicar(Product... productos) {
        List<Product> lista = new java.util.ArrayList<>(List.of(productos));
        lista.sort(Comparator.comparingDouble(Product::precio));
        when(service.getLastResult()).thenReturn(new AggregatedResult(List.copyOf(lista),
                Map.of("Sitio", lista.size()), Map.of(),
                ResultAggregator.calcularFacets(lista),
                lista.get(0).precio(), lista.get(lista.size() - 1).precio()));
    }

    private JsonNode facetsDeData(String rubroFiltro) {
        ResponseEntity<com.fasterxml.jackson.databind.node.ObjectNode> resp = controller.data(
                1, 24, null, null, null, null, null, null, null, null, rubroFiltro, null,
                "precio_asc", null, null, null, null, null, null, null, null);
        return resp.getBody().path("meta").path("facets");
    }

    // ─── rubros ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rubros cuenta productos por rubro, con la clave en minúsculas")
    void rubrosCuentaPorRubroEnMinusculas() {
        publicar(producto("u/1", 100, "indumentaria", false, 1),
                 producto("u/2", 200, "Indumentaria", false, 1),
                 producto("u/3", 300, "TECNOLOGIA", false, 1));

        JsonNode rubros = facetsDeData(null).path("rubros");

        assertThat(rubros.path("indumentaria").asInt()).isEqualTo(2);
        assertThat(rubros.path("tecnologia").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("un rubro vacío o en blanco no entra al histograma")
    void rubroEnBlancoNoEntra() {
        publicar(producto("u/1", 100, "indumentaria", false, 1),
                 producto("u/2", 200, "", false, 1),
                 producto("u/3", 300, "   ", false, 1));

        JsonNode rubros = facetsDeData(null).path("rubros");

        assertThat(rubros.size()).isEqualTo(1);
        assertThat(rubros.path("indumentaria").asInt()).isEqualTo(1);
    }

    // ─── gymratCount / packCount ─────────────────────────────────────────────

    @Test
    @DisplayName("gymratCount cuenta los productos marcados gymrat")
    void gymratCountCuentaLosMarcados() {
        publicar(producto("u/1", 100, "gym", true, 1),
                 producto("u/2", 200, "gym", true, 1),
                 producto("u/3", 300, "indumentaria", false, 1));

        assertThat(facetsDeData(null).path("gymratCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("packCount cuenta los productos con más de una unidad")
    void packCountCuentaLosDeVariasUnidades() {
        publicar(producto("u/1", 100, "suplementos", false, 3),
                 producto("u/2", 200, "suplementos", false, 1),
                 producto("u/3", 300, "suplementos", false, 6));

        assertThat(facetsDeData(null).path("packCount").asInt()).isEqualTo(2);
    }

    // ─── The property that makes them "global" ───────────────────────────────

    @Test
    @DisplayName("los contadores son del catálogo completo, no de la página filtrada")
    void losContadoresSonDelCatalogoCompletoNoDeLaPaginaFiltrada() {
        publicar(producto("u/1", 100, "gym", true, 4),
                 producto("u/2", 200, "gym", true, 1),
                 producto("u/3", 300, "tecnologia", false, 1));

        // Filtering down to `tecnologia` leaves a single non-gymrat, non-pack
        // product — the counters must still describe the whole catalog.
        JsonNode facets = facetsDeData("tecnologia");

        assertThat(facets.path("gymratCount").asInt()).isEqualTo(2);
        assertThat(facets.path("packCount").asInt()).isEqualTo(1);
        assertThat(facets.path("rubros").path("gym").asInt()).isEqualTo(2);
    }

    // ─── /api/facets publishes the same counters ─────────────────────────────

    @Test
    @DisplayName("/api/facets publica gymratCount y packCount con los mismos valores que /api/data")
    void facetsPublicaLosMismosContadores() {
        publicar(producto("u/1", 100, "gym", true, 2),
                 producto("u/2", 200, "gym", true, 1),
                 producto("u/3", 300, "indumentaria", false, 5));

        JsonNode body = controller.facets().getBody();
        JsonNode deData = facetsDeData(null);

        assertThat(body.path("gymratCount").asInt())
                .isEqualTo(deData.path("gymratCount").asInt()).isEqualTo(2);
        assertThat(body.path("packCount").asInt())
                .isEqualTo(deData.path("packCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("/api/facets NO publica el histograma de rubros — solo /api/data lo hace")
    void facetsNoPublicaRubros() {
        publicar(producto("u/1", 100, "indumentaria", false, 1));

        assertThat(controller.facets().getBody().has("rubros")).isFalse();
        assertThat(facetsDeData(null).has("rubros")).isTrue();
    }

    @Test
    @DisplayName("un catálogo sin gymrat ni packs reporta cero, no omite las claves")
    void catalogoSinGymratNiPacksReportaCero() {
        publicar(producto("u/1", 100, "indumentaria", false, 1));

        JsonNode facets = facetsDeData(null);
        assertThat(facets.path("gymratCount").asInt()).isZero();
        assertThat(facets.path("packCount").asInt()).isZero();
        assertThat(controller.facets().getBody().path("gymratCount").asInt()).isZero();
    }
}
