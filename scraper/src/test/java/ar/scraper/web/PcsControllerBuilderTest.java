package ar.scraper.web;

import com.fasterxml.jackson.databind.JsonNode;
import ar.scraper.api.ApiException;
import ar.scraper.web.support.Wire;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.Facets;
import ar.scraper.db.DatabaseService;
import ar.scraper.model.Product;
import ar.scraper.outfits.RecommendationService;
import ar.scraper.web.support.SujetoDePrueba;
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
@DisplayName("PcsController — PC builder endpoint")
class PcsControllerBuilderTest {

    private ScraperService service;
    private PcsController controller;

    @AfterEach
    void limpiarContexto() {
        SujetoDePrueba.salir();
    }

    @BeforeEach
    void setUp() {
        SujetoDePrueba.entrar("ADMIN");
        service = mock(ScraperService.class);
        DatabaseService db = mock(DatabaseService.class);
        // Real RecommendationService: PcBuilder is built inline from it in PcsController,
        // so a mock here would make baseMlScore always 0.0 — harmless for these fixtures,
        // which don't rely on ML tiebreaks, but real is closer to production wiring.
        RecommendationService recommendationService = new RecommendationService();
        controller = new PcsController(service, new ar.scraper.pcs.PcBuilder(), db.pcsGuardadas(), db.preferenciaArmador(), new ar.scraper.security.ActorResolver());
    }

    private AggregatedResult mockResult(List<Product> products) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(products, Map.of(), Map.of(), facets, 0, 0);
    }

    private Product producto(String nombre, double precio, String categoria, String url) {
        return Product.builder()
                .sitio("TestSitio")
                .nombre(nombre)
                .precio(precio)
                .precioOriginal(null)
                .url(url)
                .imagenUrl("https://img/test.jpg")
                .categoria(categoria)
                .genero("")
                .talles(List.of())
                .ml(Product.MlScore.EMPTY)
                .marca("")
                .rubro("tecnologia")
                .gymrat(false)
                .build();
    }

    @Test
    void returns204WhenNoCatalog() {
        when(service.getLastResult()).thenReturn(null);

        var resp = controller.builder(0, false, "", "");

        assertThat(resp.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void returns200WithBuildShape() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"))));

        var resp = controller.builder(0, false, "", "");
        JsonNode body = Wire.data(resp);

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

        var resp = controller.builder(0, false, "", "");
        JsonNode body = Wire.data(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("picks").size()).isEqualTo(0);
        assertThat(body.get("sinStock").size()).isEqualTo(6);
    }

    @Test
    void conGpuTrueAddsTheGpuSlot() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Placa de Video RTX 4070", 900_000, "GPU", "https://t/gpu"))));

        var resp = controller.builder(0, true, "", "");
        JsonNode body = Wire.data(resp);

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

        var resp = controller.builder(0, false, "", "");
        JsonNode body = Wire.data(resp);

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

        var resp = controller.builder(0, false, "", "alta");
        JsonNode body = Wire.data(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("picks").get(0).get("slot").asText()).isEqualTo("cpu");
        assertThat(body.get("picks").get(0).get("url").asText()).isEqualTo("https://t/r9");
    }

    @Test
    void gamaAccentAndCaseInsensitive() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/r9"))));

        var resp = controller.builder(0, false, "", "ECONÓMICA");
        JsonNode body = Wire.data(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("sinCompatible").toString()).contains("cpu");
    }

    @Test
    void gamaAbsentBehavesLikeNoGamaRequested() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Procesador Intel Core i3 12100", 100_000, "CPU", "https://t/i3"))));

        var resp = controller.builder(0, false, "", "");
        JsonNode body = Wire.data(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("sinCompatible").size()).isEqualTo(0);
        assertThat(body.get("picks").get(0).get("url").asText()).isEqualTo("https://t/i3");
    }

    @Test
    void gamaInvalidReturns400WithOkFalse() {
        ApiException e = Wire.apiError(() -> controller.builder(0, false, "", "ultra"));

        assertThat(e.status().value()).isEqualTo(400);
        assertThat(e.code()).isEqualTo("solicitud_invalida");
    }

    // ── preferencias técnicas (pc-builder-deep-taxonomy T5c) ──────────────

    @Test
    void ddrValidFiltersMotherboard() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Motherboard ASUS PRIME B650M DDR5 AM5", 200_000, "Motherboard", "https://t/ddr5"),
                producto("Motherboard ASUS PRIME B450M DDR4 AM4", 150_000, "Motherboard", "https://t/ddr4"))));

        var resp = controller.builder(0, false, "", "", "ddr4", "", "", "", null, null);
        JsonNode body = Wire.data(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("picks").get(0).get("url").asText()).isEqualTo("https://t/ddr4");
    }

    @Test
    void ddrInvalidReturns400WithOkFalse() {
        ApiException e = Wire.apiError(() -> controller.builder(0, false, "", "", "ddr3", "", "", "", null, null));

        assertThat(e.status().value()).isEqualTo(400);
        assertThat(e.code()).isEqualTo("solicitud_invalida");
        assertThat(e.getMessage()).contains("ddr");
    }

    @Test
    void marcaCpuValidFiltersCpu() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Procesador Intel Core i5 12400F", 150_000, "CPU", "https://t/intel"),
                producto("Procesador AMD Ryzen 5 5600X AM4", 140_000, "CPU", "https://t/amd"))));

        var resp = controller.builder(0, false, "", "", "", "amd", "", "", null, null);
        JsonNode body = Wire.data(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("picks").get(0).get("url").asText()).isEqualTo("https://t/amd");
    }

    @Test
    void marcaCpuInvalidReturns400WithOkFalse() {
        ApiException e = Wire.apiError(() -> controller.builder(0, false, "", "", "", "nvidia", "", "", null, null));

        assertThat(e.status().value()).isEqualTo(400);
        assertThat(e.code()).isEqualTo("solicitud_invalida");
        assertThat(e.getMessage()).contains("marcaCpu");
    }

    @Test
    void marcaGpuValidFiltersGpuWhenConGpu() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Placa de Video RTX 4070", 900_000, "GPU", "https://t/nvidia"),
                producto("Placa de Video Radeon RX 7800 XT", 800_000, "GPU", "https://t/amd"))));

        var resp = controller.builder(0, true, "", "", "", "", "amd", "", null, null);
        JsonNode body = Wire.data(resp);

        String gpuUrl = null;
        for (var pick : body.get("picks")) {
            if ("gpu".equals(pick.get("slot").asText())) gpuUrl = pick.get("url").asText();
        }
        assertThat(gpuUrl).isEqualTo("https://t/amd");
    }

    @Test
    void marcaGpuInvalidReturns400WithOkFalse() {
        ApiException e = Wire.apiError(() -> controller.builder(0, true, "", "", "", "", "intel", "", null, null));

        assertThat(e.status().value()).isEqualTo(400);
        assertThat(e.code()).isEqualTo("solicitud_invalida");
        assertThat(e.getMessage()).contains("marcaGpu");
    }

    @Test
    void tipoAlmacenamientoValidFiltersAlmacenamiento() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("SSD Kingston NV2 1TB M.2 NVMe", 60_000, "Almacenamiento", "https://t/nvme"),
                producto("Disco Rigido Seagate 1TB HDD", 40_000, "Almacenamiento", "https://t/hdd"))));

        var resp = controller.builder(0, false, "", "", "", "", "", "hdd", null, null);
        JsonNode body = Wire.data(resp);

        String almacenamientoUrl = null;
        for (var pick : body.get("picks")) {
            if ("almacenamiento".equals(pick.get("slot").asText())) almacenamientoUrl = pick.get("url").asText();
        }
        assertThat(almacenamientoUrl).isEqualTo("https://t/hdd");
    }

    @Test
    void tipoAlmacenamientoInvalidReturns400WithOkFalse() {
        ApiException e = Wire.apiError(() -> controller.builder(0, false, "", "", "", "", "", "ssd", null, null));

        assertThat(e.status().value()).isEqualTo(400);
        assertThat(e.code()).isEqualTo("solicitud_invalida");
        assertThat(e.getMessage()).contains("tipoAlmacenamiento");
    }

    @Test
    void ramDualTrueFiltersToTheKit() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Memoria Corsair DDR5 32GB (2x16GB) 6000MHz", 180_000, "RAM", "https://t/kit"),
                producto("Memoria Corsair DDR5 32GB 6000MHz", 170_000, "RAM", "https://t/single"))));

        var resp = controller.builder(0, false, "", "", "", "", "", "", true, null);
        JsonNode body = Wire.data(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(body.get("picks").get(0).get("url").asText()).isEqualTo("https://t/kit");
    }

    @Test
    void wifiTrueFiltersMotherboard() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("Motherboard ASUS TUF Gaming B650M-E WiFi AM5", 200_000, "Motherboard", "https://t/wifi"),
                producto("Motherboard ASUS TUF Gaming B650M-E AM5", 190_000, "Motherboard", "https://t/nowifi"))));

        var resp = controller.builder(0, false, "", "", "", "", "", "", null, true);
        JsonNode body = Wire.data(resp);

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

        var resp = controller.builder(0, false, "", "", "", "", "", "", null, null,
                null, "", "", null, "homelab");
        JsonNode body = Wire.data(resp);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        java.util.List<String> slots = new java.util.ArrayList<>();
        body.get("picks").forEach(p -> slots.add(p.get("slot").asText()));
        assertThat(slots).contains("sistema", "datos").doesNotContain("almacenamiento");
    }

    @Test
    void usoAusenteSigueSiendoGaming() {
        when(service.getLastResult()).thenReturn(mockResult(List.of(
                producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/nvme"))));

        var resp = controller.builder(0, false, "", "", "", "", "", "", null, null,
                null, "", "", null, "");
        JsonNode body = Wire.data(resp);

        java.util.List<String> slots = new java.util.ArrayList<>();
        body.get("picks").forEach(p -> slots.add(p.get("slot").asText()));
        assertThat(slots).contains("almacenamiento").doesNotContain("sistema", "datos");
    }

    @Test
    void usoInvalidoReturns400WithOkFalse() {
        ApiException e = Wire.apiError(() -> controller.builder(0, false, "", "", "", "", "", "", null, null,
                null, "", "", null, "servidor"));

        assertThat(e.status().value()).isEqualTo(400);
        assertThat(e.code()).isEqualTo("solicitud_invalida");
        assertThat(e.getMessage()).contains("uso");
    }
}
