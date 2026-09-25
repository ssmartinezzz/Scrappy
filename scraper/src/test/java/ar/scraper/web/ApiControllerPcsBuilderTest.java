package ar.scraper.web;

import ar.scraper.indices.IndiceService;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.catalog.Facets;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import ar.scraper.outfits.OutfitService;
import ar.scraper.outfits.RecommendationService;
import ar.scraper.web.support.SujetoDePrueba;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Epic("REST API")
@Feature("PCs")
@DisplayName("ApiController — PC builder endpoint")
class ApiControllerPcsBuilderTest {

    private ScraperService service;
    private ApiController controller;

    @AfterEach
    void limpiarContexto() {
        SujetoDePrueba.salir();
    }

    @BeforeEach
    void setUp() {
        SujetoDePrueba.entrar("ADMIN");
        service = mock(ScraperService.class);
        IndiceService indiceService = mock(IndiceService.class);
        ScraperConfig config = mock(ScraperConfig.class);
        ResultAggregator aggregator = mock(ResultAggregator.class);
        DatabaseService db = mock(DatabaseService.class);
        GroupingService grouping = mock(GroupingService.class);
        PythonRunner pythonRunner = mock(PythonRunner.class);
        OutfitService outfitService = mock(OutfitService.class);
        // Real RecommendationService: PcBuilder is built inline from it in ApiController,
        // so a mock here would make baseMlScore always 0.0 — harmless for these fixtures,
        // which don't rely on ML tiebreaks, but real is closer to production wiring.
        RecommendationService recommendationService = new RecommendationService();
        controller = new ApiController(service, indiceService, config, aggregator,
                db, grouping, pythonRunner, outfitService, recommendationService);
    }

    private AggregatedResult mockResult(List<Product> products) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(products, Map.of(), Map.of(), facets, 0, 0);
    }

    private Product producto(String nombre, double precio, String categoria, String url) {
        return new Product("TestSitio", nombre, precio, null, url, "https://img/test.jpg",
                categoria, "", List.of(), Product.MlScore.EMPTY, "", "tecnologia", false);
    }

    @Test
    void returns204WhenNoCatalog() {
        when(service.getLastResult()).thenReturn(null);

        var resp = controller.pcsBuilder(0, false, "");

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void returns200WithBuildShape() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"))));

        var resp = controller.pcsBuilder(0, false, "");
        ObjectNode body = resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.has("picks")).isTrue();
        assertThat(body.has("sinStock")).isTrue();
        assertThat(body.has("sinCompatible")).isTrue();
        assertThat(body.has("presupuesto")).isTrue();
        assertThat(body.has("totalEstimado")).isTrue();
        assertThat(body.get("picks").isArray()).isTrue();
        assertThat(body.get("picks").size()).isEqualTo(2);
        assertThat(body.get("picks").get(0).get("slot").asText()).isEqualTo("mother");
        assertThat(body.get("picks").get(0).get("specs").get("socket").asText()).isEqualTo("AM5");
        assertThat(body.get("sinStock").isArray()).isTrue();
    }

    @Test
    void returns200WithEmptyPicksWhenCatalogHasNoTechParts() {
        when(service.getLastResult()).thenReturn(mockResult(List.of()));

        var resp = controller.pcsBuilder(0, false, "");
        ObjectNode body = resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("picks").size()).isEqualTo(0);
        assertThat(body.get("sinStock").size()).isEqualTo(6);
    }

    @Test
    void conGpuTrueAddsTheGpuSlot() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Placa de Video RTX 4070", 900_000, "GPU", "https://t/gpu"))));

        var resp = controller.pcsBuilder(0, true, "");
        ObjectNode body = resp.getBody();

        boolean tieneGpu = false;
        for (var pick : body.get("picks")) {
            if ("gpu".equals(pick.get("slot").asText())) tieneGpu = true;
        }
        assertThat(tieneGpu).isTrue();
    }

    @Test
    void conGpuFalseNeverReturnsTheGpuSlot() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Placa de Video RTX 4070", 900_000, "GPU", "https://t/gpu"))));

        var resp = controller.pcsBuilder(0, false, "");
        ObjectNode body = resp.getBody();

        for (var pick : body.get("picks")) {
            assertThat(pick.get("slot").asText()).isNotEqualTo("gpu");
        }
        for (var s : body.get("sinStock")) {
            assertThat(s.asText()).isNotEqualTo("gpu");
        }
    }

    // ── gama query param (pc-builder-gama T6) ─────────────────────────────

    @Test
    void gamaValidReachesBuilderWithTheMappedGama() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Procesador Intel Core i3 12100", 100_000, "CPU", "https://t/i3"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/r9"))));

        var resp = controller.pcsBuilder(0, false, "", "alta");
        ObjectNode body = resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("picks").get(0).get("slot").asText()).isEqualTo("cpu");
        assertThat(body.get("picks").get(0).get("url").asText()).isEqualTo("https://t/r9");
    }

    @Test
    void gamaAccentAndCaseInsensitive() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/r9"))));

        var resp = controller.pcsBuilder(0, false, "", "ECONÓMICA");
        ObjectNode body = resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("sinCompatible").toString()).contains("cpu");
    }

    @Test
    void gamaAbsentBehavesLikeNoGamaRequested() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Procesador Intel Core i3 12100", 100_000, "CPU", "https://t/i3"))));

        var resp = controller.pcsBuilder(0, false, "", "");
        ObjectNode body = resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("sinCompatible").size()).isEqualTo(0);
        assertThat(body.get("picks").get(0).get("url").asText()).isEqualTo("https://t/i3");
    }

    @Test
    void gamaInvalidReturns400WithOkFalse() {
        var resp = controller.pcsBuilder(0, false, "", "ultra");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("ok").asBoolean()).isFalse();
    }

    // ── preferencias técnicas (pc-builder-deep-taxonomy T5c) ──────────────

    @Test
    void ddrValidFiltersMotherboard() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Motherboard ASUS PRIME B650M DDR5 AM5", 200_000, "Motherboard", "https://t/ddr5"),
                producto("Motherboard ASUS PRIME B450M DDR4 AM4", 150_000, "Motherboard", "https://t/ddr4"))));

        var resp = controller.pcsBuilder(0, false, "", "", "ddr4", "", "", "", null, null);
        ObjectNode body = resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("picks").get(0).get("url").asText()).isEqualTo("https://t/ddr4");
    }

    @Test
    void ddrInvalidReturns400WithOkFalse() {
        var resp = controller.pcsBuilder(0, false, "", "", "ddr3", "", "", "", null, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("ok").asBoolean()).isFalse();
        assertThat(resp.getBody().get("mensaje").asText()).contains("ddr");
    }

    @Test
    void marcaCpuValidFiltersCpu() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Procesador Intel Core i5 12400F", 150_000, "CPU", "https://t/intel"),
                producto("Procesador AMD Ryzen 5 5600X AM4", 140_000, "CPU", "https://t/amd"))));

        var resp = controller.pcsBuilder(0, false, "", "", "", "amd", "", "", null, null);
        ObjectNode body = resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("picks").get(0).get("url").asText()).isEqualTo("https://t/amd");
    }

    @Test
    void marcaCpuInvalidReturns400WithOkFalse() {
        var resp = controller.pcsBuilder(0, false, "", "", "", "nvidia", "", "", null, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("ok").asBoolean()).isFalse();
        assertThat(resp.getBody().get("mensaje").asText()).contains("marcaCpu");
    }

    @Test
    void marcaGpuValidFiltersGpuWhenConGpu() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Placa de Video RTX 4070", 900_000, "GPU", "https://t/nvidia"),
                producto("Placa de Video Radeon RX 7800 XT", 800_000, "GPU", "https://t/amd"))));

        var resp = controller.pcsBuilder(0, true, "", "", "", "", "amd", "", null, null);
        ObjectNode body = resp.getBody();

        String gpuUrl = null;
        for (var pick : body.get("picks")) {
            if ("gpu".equals(pick.get("slot").asText())) gpuUrl = pick.get("url").asText();
        }
        assertThat(gpuUrl).isEqualTo("https://t/amd");
    }

    @Test
    void marcaGpuInvalidReturns400WithOkFalse() {
        var resp = controller.pcsBuilder(0, true, "", "", "", "", "intel", "", null, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("ok").asBoolean()).isFalse();
        assertThat(resp.getBody().get("mensaje").asText()).contains("marcaGpu");
    }

    @Test
    void tipoAlmacenamientoValidFiltersAlmacenamiento() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("SSD Kingston NV2 1TB M.2 NVMe", 60_000, "Almacenamiento", "https://t/nvme"),
                producto("Disco Rigido Seagate 1TB HDD", 40_000, "Almacenamiento", "https://t/hdd"))));

        var resp = controller.pcsBuilder(0, false, "", "", "", "", "", "hdd", null, null);
        ObjectNode body = resp.getBody();

        String almacenamientoUrl = null;
        for (var pick : body.get("picks")) {
            if ("almacenamiento".equals(pick.get("slot").asText())) almacenamientoUrl = pick.get("url").asText();
        }
        assertThat(almacenamientoUrl).isEqualTo("https://t/hdd");
    }

    @Test
    void tipoAlmacenamientoInvalidReturns400WithOkFalse() {
        var resp = controller.pcsBuilder(0, false, "", "", "", "", "", "ssd", null, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("ok").asBoolean()).isFalse();
        assertThat(resp.getBody().get("mensaje").asText()).contains("tipoAlmacenamiento");
    }

    @Test
    void ramDualTrueFiltersToTheKit() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Memoria Corsair DDR5 32GB (2x16GB) 6000MHz", 180_000, "RAM", "https://t/kit"),
                producto("Memoria Corsair DDR5 32GB 6000MHz", 170_000, "RAM", "https://t/single"))));

        var resp = controller.pcsBuilder(0, false, "", "", "", "", "", "", true, null);
        ObjectNode body = resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("picks").get(0).get("url").asText()).isEqualTo("https://t/kit");
    }

    @Test
    void wifiTrueFiltersMotherboard() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Motherboard ASUS TUF Gaming B650M-E WiFi AM5", 200_000, "Motherboard", "https://t/wifi"),
                producto("Motherboard ASUS TUF Gaming B650M-E AM5", 190_000, "Motherboard", "https://t/nowifi"))));

        var resp = controller.pcsBuilder(0, false, "", "", "", "", "", "", null, true);
        ObjectNode body = resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("picks").get(0).get("url").asText()).isEqualTo("https://t/wifi");
    }

    // ── uso (pc-builder-homelab T5) ─────────────────────────────────────

    @Test
    void usoHomelabArmaSistemaYDatos() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"),
                producto("Memoria RAM Corsair Vengeance DDR5 64GB (2x32GB) 6000MHz", 180_000, "RAM", "https://t/ram"),
                producto("Fuente Antec 750W 80 Plus Bronze ATX 3.1", 120_000, "Fuente", "https://t/fuente"),
                producto("Gabinete Corsair 4000D ATX", 90_000, "Gabinete", "https://t/gabinete"),
                producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/nvme"),
                producto("HDD Seagate Ironwolf 4TB NAS", 200_000, "Almacenamiento", "https://t/hdd"))));

        var resp = controller.pcsBuilder(0, false, "", "", "", "", "", "", null, null,
                null, "", "", null, "homelab");
        ObjectNode body = resp.getBody();

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        java.util.List<String> slots = new java.util.ArrayList<>();
        body.get("picks").forEach(p -> slots.add(p.get("slot").asText()));
        assertThat(slots).contains("sistema", "datos").doesNotContain("almacenamiento");
    }

    @Test
    void usoAusenteSigueSiendoGaming() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/nvme"))));

        var resp = controller.pcsBuilder(0, false, "", "", "", "", "", "", null, null,
                null, "", "", null, "");
        ObjectNode body = resp.getBody();

        java.util.List<String> slots = new java.util.ArrayList<>();
        body.get("picks").forEach(p -> slots.add(p.get("slot").asText()));
        assertThat(slots).contains("almacenamiento").doesNotContain("sistema", "datos");
    }

    @Test
    void usoInvalidoReturns400WithOkFalse() {
        var resp = controller.pcsBuilder(0, false, "", "", "", "", "", "", null, null,
                null, "", "", null, "servidor");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("ok").asBoolean()).isFalse();
        assertThat(resp.getBody().get("mensaje").asText()).contains("uso");
    }
}
