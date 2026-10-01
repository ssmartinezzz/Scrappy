package ar.scraper.web;

import ar.scraper.web.support.Wire;
import ar.scraper.outfits.OutfitService;
import ar.scraper.outfits.RecommendationService;


import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.model.Product.MlScore;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the infantil veto in {@code MarcasPicksController.mejoresPorCategoria()}
 * (category-brand-quality-fixes, Phase 2). Mirrors
 * {@code CatalogoPrecioRangeTest}'s Mockito-mock constructor convention.
 *
 * <p>Spec requirement: {@code GET /api/mejores} MUST exclude products whose
 * {@code genero == "infantil"} from every category card, mirroring the veto
 * already applied by {@code RecommendationService.GENERO_VETADO} and
 * {@code OutfitService.generoElegible()}.</p>
 */
@Epic("REST API")
@Feature("Mejores Picks / Recomendados")
@Story("Infantil veto")
@DisplayName("MarcasPicksController — Mejores Picks infantil veto")
class MarcasPicksInfantilVetoTest {

    private ScraperService service;
    private GroupingService grouping;
    private MarcasPicksController controller;

    @BeforeEach
    void setUp() {
        wireController();
    }

    @Step("Wire MarcasPicksController with mocked collaborators")
    private void wireController() {
        service          = mock(ScraperService.class);
        grouping          = mock(GroupingService.class);
        controller = new MarcasPicksController(service, new ar.scraper.web.cache.CatalogoDerivadoCache(service, grouping));
    }

    private Product producto(String url, String genero) {
        return new Product("Sitio", "Producto " + url, 10000, null, url, "img",
                "Zapatilla", genero, List.of(), MlScore.EMPTY, "Marca", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, SenalFinanciacion.EMPTY);
    }

    private AggregatedResult resultFor(Product... productos) {
        List<Product> lista = List.of(productos);
        double min = lista.stream().mapToDouble(Product::precio).min().orElse(0);
        double max = lista.stream().mapToDouble(Product::precio).max().orElse(0);
        return new AggregatedResult(lista, Map.of("Sitio", lista.size()), Map.of(),
                ResultAggregator.calcularFacets(lista), min, max);
    }

    // ── Infantil product is excluded from its category card ────────────────

    @Test
    void infantilProductoExcluidoDeCategoria() {
        Product infantil = producto("https://site.com/infantil", "infantil");
        when(service.getLastResult()).thenReturn(resultFor(infantil));

        ResponseEntity<?> resp = controller.mejoresPorCategoria(null);

        JsonNode body = Wire.data(resp);
        // No eligible products remain in "Zapatilla" -> no category card at all.
        boolean tieneZapatillas = false;
        if (body != null) {
            for (JsonNode node : body) {
                if ("Zapatilla".equals(node.path("categoria").asText())) {
                    tieneZapatillas = true;
                }
            }
        }
        assertThat(tieneZapatillas).isFalse();
    }

    // ── Non-infantil products remain eligible (regression guard) ────────────

    @Test
    void nonInfantilProductoElegible() {
        Product hombre = producto("https://site.com/hombre", "hombre");
        Product mujer  = producto("https://site.com/mujer", "mujer");
        Product unisex = producto("https://site.com/unisex", "unisex");
        when(service.getLastResult()).thenReturn(resultFor(hombre, mujer, unisex));

        ResponseEntity<?> resp = controller.mejoresPorCategoria(null);

        JsonNode body = Wire.data(resp);
        JsonNode zapatillasNode = null;
        for (JsonNode node : body) {
            if ("Zapatilla".equals(node.path("categoria").asText())) {
                zapatillasNode = node;
            }
        }
        assertThat(zapatillasNode).isNotNull();
        assertThat(zapatillasNode.path("count").asInt()).isEqualTo(3);
    }

    // ── Missing/blank genero is NOT vetoed ──────────────────────────────────

    @Test
    void generoNuloOVacioNoEsVetado() {
        Product generoNulo  = producto("https://site.com/null", null);
        Product generoVacio = producto("https://site.com/vacio", "");
        when(service.getLastResult()).thenReturn(resultFor(generoNulo, generoVacio));

        ResponseEntity<?> resp = controller.mejoresPorCategoria(null);

        JsonNode body = Wire.data(resp);
        JsonNode zapatillasNode = null;
        for (JsonNode node : body) {
            if ("Zapatilla".equals(node.path("categoria").asText())) {
                zapatillasNode = node;
            }
        }
        assertThat(zapatillasNode).isNotNull();
        assertThat(zapatillasNode.path("count").asInt()).isEqualTo(2);
    }
}
