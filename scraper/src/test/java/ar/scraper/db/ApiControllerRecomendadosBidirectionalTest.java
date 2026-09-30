package ar.scraper.db;

import ar.scraper.web.support.Wire;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.web.OutfitsController;
import ar.scraper.web.RecomendadosController;
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
 * Integration test confirming the bidirectional shared taste signal between
 * the outfit-builder and the new recommendations feed (spec.md "Shared
 * Taste Signal Across Surfaces"). Uses a REAL temp-file {@link DatabaseService}
 * (not mocked) so that writes via one endpoint are actually visible to reads
 * from the other endpoint — mirrors {@code DatabaseServicePresetTest}'s real
 * SQLite seam, applied at the {@code ApiController} level.
 */
@Epic("REST API")
@Feature("Mejores Picks / Recomendados")
@Story("Recomendados bidirectional")
@DisplayName("ApiController — Recomendados bidirectional taste signal")
class ApiControllerRecomendadosBidirectionalTest extends PostgresTestBase {

    private DatabaseService db;
    private OutfitsController controller;
    private RecomendadosController recomendadosController;
    private ScraperService service;

    private Product producto(String url, String marca, String categoria) {
        return producto(url, marca, categoria, "hombre");
    }

    private Product producto(String url, String marca, String categoria, String genero) {
        return new Product("TestSitio", "Producto " + url, 10000, null, url, "img",
                categoria, genero, List.of(), Product.MlScore.EMPTY, marca, "indumentaria", true);
    }

    @AfterEach
    void limpiarContextoDeSeguridad() {
        SujetoDePrueba.salir();
    }

    @BeforeEach
    void setUp() {
        wireController();
    }

    @Step("Wire ApiController with a real temp-file DatabaseService and mocked collaborators")
    private void wireController() {
        db = TestDatabaseServices.create(dataSource());

        service           = mock(ScraperService.class);
        RecommendationService recommendationService = new RecommendationService();
        OutfitService outfitService       = new OutfitService(recommendationService);

        SujetoDePrueba.entrar(dataSource(), "ADMIN");

        controller = new OutfitsController(service, db.feedback(), db.outfitsGuardados(), outfitService,
                new ar.scraper.security.ActorResolver());
        recomendadosController = new RecomendadosController(service, db.feedback(), recommendationService,
                new ar.scraper.security.ActorResolver());
    }


    @Test
    void dislikeViaRecomendadosFeedbackExcludesPairFromOutfits() {
        Product puma = producto("https://t/puma-buzo", "Puma", "Buzo");
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(List.of(puma));
        when(service.getLastResult()).thenReturn(result);

        recomendadosController.recomendadosFeedback(Map.of(
                "genero", "hombre",
                "items", List.of(Map.of("url", "https://t/puma-buzo", "liked", false))
        ));

        ResponseEntity<?> outfitsResp =
                controller.outfits("hombre", 0.0, "", 0.0);

        JsonNode slots = Wire.data(outfitsResp).get("slots");
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

        ResponseEntity<?> recoResp =
                recomendadosController.recomendados(0, 24, null, null);

        JsonNode items = Wire.data(recoResp);
        // Nike|Zapatilla boosted -> must rank first (equal base ML score otherwise).
        assertThat(items.get(0).get("marca").asText()).isEqualTo("Nike");
    }

    @Test
    void unisexProductAlwaysEligibleRegardlessOfRequestedGenero() {
        Product unisexRemera = producto("https://t/unisex-remera", "Vans", "Remera", "unisex");
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(List.of(unisexRemera));
        when(service.getLastResult()).thenReturn(result);

        Allure.parameter("genero", "mujer");
        ResponseEntity<?> resp =
                recomendadosController.recomendados(0, 24, "mujer", "Remera");

        JsonNode items = Wire.data(resp);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("nombre").asText()).isEqualTo("Producto https://t/unisex-remera");
    }

    @Test
    void noGeneroParamStillBridgesAndExcludesInfantil() {
        Product hombre   = producto("https://t/hombre", "Nike", "Zapatilla", "hombre");
        Product mujer    = producto("https://t/mujer", "Puma", "Zapatilla", "mujer");
        Product unisex   = producto("https://t/unisex", "Vans", "Zapatilla", "unisex");
        Product infantil = producto("https://t/infantil", "Nike", "Zapatilla", "infantil");
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(List.of(hombre, mujer, unisex, infantil));
        when(service.getLastResult()).thenReturn(result);

        ResponseEntity<?> resp =
                recomendadosController.recomendados(0, 24, null, "Zapatilla");

        JsonNode items = Wire.data(resp);
        List<String> nombres = new java.util.ArrayList<>();
        for (JsonNode n : items) nombres.add(n.get("nombre").asText());

        assertThat(nombres).containsExactlyInAnyOrder(
                "Producto https://t/hombre", "Producto https://t/mujer", "Producto https://t/unisex");
        assertThat(nombres).doesNotContain("Producto https://t/infantil");
    }

    @Test
    void insufficientOwnGeneroStockTriggersOppositeGeneroFallback() {
        List<Product> productos = new java.util.ArrayList<>();
        // categoria "Camperas": zero mujer, zero unisex, only hombre stock.
        for (int i = 0; i < 3; i++) {
            productos.add(producto("https://t/campera-hombre-" + i, "Nike", "Camperas", "hombre"));
        }
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(productos);
        when(service.getLastResult()).thenReturn(result);

        ResponseEntity<?> resp =
                recomendadosController.recomendados(0, 24, "mujer", "Camperas");

        JsonNode items = Wire.data(resp);
        // Step 1 and step 2 both yield zero -> step 3 fallback admits hombre stock.
        assertThat(items).hasSize(3);
    }

    @Test
    void sufficientOwnGeneroStockDoesNotRelaxToOppositeGenero() {
        List<Product> productos = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            productos.add(producto("https://t/pantalon-mujer-" + i, "Adidas", "Pantalón", "mujer"));
        }
        for (int i = 0; i < 8; i++) {
            productos.add(producto("https://t/pantalon-hombre-" + i, "Nike", "Pantalón", "hombre"));
        }
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(productos);
        when(service.getLastResult()).thenReturn(result);

        ResponseEntity<?> resp =
                recomendadosController.recomendados(0, 24, "mujer", "Pantalón");

        JsonNode items = Wire.data(resp);
        for (JsonNode n : items) {
            assertThat(n.get("marca").asText()).isNotEqualTo("Nike");
        }
        assertThat(items).hasSize(8);
    }

    @Test
    void infantilExcludedEvenAsRelaxationFallbackCandidate() {
        List<Product> productos = new java.util.ArrayList<>();
        // categoria "Zapatilla": zero hombre/mujer/unisex, only infantil stock.
        productos.add(producto("https://t/zap-infantil", "Nike", "Zapatilla", "infantil"));
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(productos);
        when(service.getLastResult()).thenReturn(result);

        Allure.parameter("genero", "hombre");
        ResponseEntity<?> resp =
                recomendadosController.recomendados(0, 24, "hombre", "Zapatilla");

        JsonNode items = Wire.data(resp);
        assertThat(items).isEmpty();
    }
}
