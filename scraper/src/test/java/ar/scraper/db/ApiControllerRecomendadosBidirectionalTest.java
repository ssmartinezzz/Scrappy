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
        return producto(url, marca, categoria, "hombre");
    }

    private Product producto(String url, String marca, String categoria, String genero) {
        return new Product("TestSitio", "Producto " + url, 10000, null, url, "img",
                categoria, genero, List.of(), Product.MlScore.EMPTY, marca, "indumentaria", true);
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
                controller.outfits("hombre", 0, "");

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

    @Test
    void unisexProductAlwaysEligibleRegardlessOfRequestedGenero() {
        Product unisexRemera = producto("https://t/unisex-remera", "Vans", "Remeras", "unisex");
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(List.of(unisexRemera));
        when(service.getLastResult()).thenReturn(result);

        ResponseEntity<com.fasterxml.jackson.databind.node.ObjectNode> resp =
                controller.recomendados(1, 24, "mujer", "Remeras");

        JsonNode items = resp.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("nombre").asText()).isEqualTo("Producto https://t/unisex-remera");
    }

    @Test
    void noGeneroParamStillBridgesAndExcludesInfantil() {
        Product hombre   = producto("https://t/hombre", "Nike", "Zapatillas", "hombre");
        Product mujer    = producto("https://t/mujer", "Puma", "Zapatillas", "mujer");
        Product unisex   = producto("https://t/unisex", "Vans", "Zapatillas", "unisex");
        Product infantil = producto("https://t/infantil", "Nike", "Zapatillas", "infantil");
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(List.of(hombre, mujer, unisex, infantil));
        when(service.getLastResult()).thenReturn(result);

        ResponseEntity<com.fasterxml.jackson.databind.node.ObjectNode> resp =
                controller.recomendados(1, 24, null, "Zapatillas");

        JsonNode items = resp.getBody().get("items");
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

        ResponseEntity<com.fasterxml.jackson.databind.node.ObjectNode> resp =
                controller.recomendados(1, 24, "mujer", "Camperas");

        JsonNode items = resp.getBody().get("items");
        // Step 1 and step 2 both yield zero -> step 3 fallback admits hombre stock.
        assertThat(items).hasSize(3);
    }

    @Test
    void sufficientOwnGeneroStockDoesNotRelaxToOppositeGenero() {
        List<Product> productos = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            productos.add(producto("https://t/pantalon-mujer-" + i, "Adidas", "Pantalones", "mujer"));
        }
        for (int i = 0; i < 8; i++) {
            productos.add(producto("https://t/pantalon-hombre-" + i, "Nike", "Pantalones", "hombre"));
        }
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(productos);
        when(service.getLastResult()).thenReturn(result);

        ResponseEntity<com.fasterxml.jackson.databind.node.ObjectNode> resp =
                controller.recomendados(1, 24, "mujer", "Pantalones");

        JsonNode items = resp.getBody().get("items");
        for (JsonNode n : items) {
            assertThat(n.get("marca").asText()).isNotEqualTo("Nike");
        }
        assertThat(items).hasSize(8);
    }

    @Test
    void infantilExcludedEvenAsRelaxationFallbackCandidate() {
        List<Product> productos = new java.util.ArrayList<>();
        // categoria "Zapatillas": zero hombre/mujer/unisex, only infantil stock.
        productos.add(producto("https://t/zap-infantil", "Nike", "Zapatillas", "infantil"));
        AggregatedResult result = mock(AggregatedResult.class);
        when(result.productos()).thenReturn(productos);
        when(service.getLastResult()).thenReturn(result);

        ResponseEntity<com.fasterxml.jackson.databind.node.ObjectNode> resp =
                controller.recomendados(1, 24, "hombre", "Zapatillas");

        JsonNode items = resp.getBody().get("items");
        assertThat(items).isEmpty();
    }
}
