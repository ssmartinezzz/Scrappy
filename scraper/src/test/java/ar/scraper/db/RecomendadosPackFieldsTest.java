package ar.scraper.db;

import ar.scraper.web.support.Wire;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.model.Product.MlScore;
import ar.scraper.model.Product.SenalFinanciacion;
import ar.scraper.web.RecomendadosController;
import ar.scraper.outfits.OutfitService;
import ar.scraper.outfits.RecommendationService;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.JsonNode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that the "Para ti" feed ({@code GET /api/recomendados}, serialized by
 * {@code escribirProducto}) exposes pack unit-price metadata — {@code esPack},
 * {@code cantidadUnidades}, {@code precioUnitario} — so {@code ProductCard}
 * renders a pack's per-unit price instead of only the (misleadingly high) shelf
 * price. Parity with the catalog ({@code /api/data}) and Mejores Picks
 * ({@code /api/mejores}) which already emit these fields.
 *
 * <p>Lives in {@code ar.scraper.db} (like
 * {@code RecomendadosBidirectionalTest}) to use a REAL temp-file
 * {@link DatabaseService}, because {@code recomendados} reads feedback/dismiss
 * state from the DB.</p>
 */
@Epic("REST API")
@Feature("Mejores Picks / Recomendados")
@Story("Recomendados pack fields")
@DisplayName("RecomendadosController — Recomendados pack unit-price fields")
class RecomendadosPackFieldsTest extends PostgresTestBase {

    private DatabaseService db;
    private ScraperService service;
    private RecomendadosController controller;

    private Product packProducto(String url, double precio, int cantidadUnidades) {
        return Product.builder()
                .sitio("Sitio")
                .nombre("Producto " + url)
                .precio(precio)
                .precioOriginal(null)
                .url(url)
                .imagenUrl("img")
                .categoria("Medias")
                .genero("hombre")
                .talles(List.of())
                .ml(MlScore.EMPTY)
                .marca("Marca")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(SenalFinanciacion.EMPTY)
                .cantidadUnidades(cantidadUnidades)
                .subCategoria("")
                .build();
    }

    private AggregatedResult resultWith(Product p) {
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(List.of(p));
        return result;
    }

    private JsonNode firstItem(ResponseEntity<?> resp) {
        return Wire.data(resp).get(0);
    }

    @AfterEach
    void limpiarContextoDeSeguridad() {
        SujetoDePrueba.salir();
    }

    @BeforeEach
    void setUp() {
        wireController();
    }

    @Step("Wire RecomendadosController with a real temp-file DatabaseService and mocked collaborators")
    private void wireController() {
        db = TestDatabaseServices.create(dataSource());

        service = mock(ScraperService.class);
        RecommendationService recommendationService = new RecommendationService();
        OutfitService outfitService       = new OutfitService(recommendationService);

        SujetoDePrueba.entrar(dataSource(), "ADMIN");

        controller = new RecomendadosController(service, db.feedback(), recommendationService, new ar.scraper.security.ActorResolver());
    }


    @Test
    void packItemExposesUnitPriceFields() {
        Product pack = packProducto("https://t/pack-medias-x3", 15000, 3);
        AggregatedResult result = resultWith(pack);
        when(service.getLastResult()).thenReturn(result);

        ResponseEntity<?> resp = controller.recomendados(0, 24, null, null);

        JsonNode item = firstItem(resp);
        assertThat(item.path("esPack").asBoolean()).isTrue();
        assertThat(item.path("cantidadUnidades").asInt()).isEqualTo(3);
        assertThat(item.path("precioUnitario").asDouble()).isEqualTo(5000.0, offset(0.001));
        assertThat(item.path("precio").asDouble()).isEqualTo(15000.0, offset(0.001));
    }

    @Test
    void nonPackItemUnitPriceEqualsShelfPrice() {
        Product single = packProducto("https://t/single", 9000, 1);
        AggregatedResult result = resultWith(single);
        when(service.getLastResult()).thenReturn(result);

        ResponseEntity<?> resp = controller.recomendados(0, 24, null, null);

        JsonNode item = firstItem(resp);
        assertThat(item.path("esPack").asBoolean()).isFalse();
        assertThat(item.path("cantidadUnidades").asInt()).isEqualTo(1);
        assertThat(item.path("precioUnitario").asDouble())
                .isEqualTo(item.path("precio").asDouble(), offset(0.001));
        assertThat(item.path("precioUnitario").asDouble()).isEqualTo(9000.0, offset(0.001));
    }
}
