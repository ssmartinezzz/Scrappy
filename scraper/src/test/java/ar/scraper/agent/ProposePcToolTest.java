package ar.scraper.agent;

import ar.scraper.aggregator.CatalogSnapshotPort;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.Facets;
import ar.scraper.model.Product;
import ar.scraper.outfits.RecommendationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RED→GREEN coverage for {@link ProposePcTool} (pc-builder-agent-tool, T2):
 * {@code propose_pc} runs {@code PcBuilder.armar} over the live snapshot and
 * returns the same JSON shape {@code GET /api/pcs/builder} serves. Read-only:
 * it never writes anything.
 */
@Epic("LLM Catalog Agent")
@Feature("Catalog tools")
@Story("propose_pc — armar una PC")
@DisplayName("ProposePcTool")
class ProposePcToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RecommendationService recommendationService = new RecommendationService();

    private Product producto(String nombre, double precio, String categoria, String url) {
        return new Product("TestSitio", nombre, precio, null, url, "https://img/test.jpg",
                categoria, "", List.of(), Product.MlScore.EMPTY, "", "tecnologia", false);
    }

    private AggregatedResult mockResult(List<Product> products) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(products, Map.of(), Map.of(), facets, 0, 0);
    }

    private CatalogSnapshotPort catalogoCon(List<Product> products) {
        CatalogSnapshotPort catalogo = mock(CatalogSnapshotPort.class);
        when(catalogo.getLastResult()).thenReturn(mockResult(products));
        return catalogo;
    }

    private List<Product> catalogoBase() {
        return List.of(
                producto("Motherboard ASUS TUF Gaming B850M-E WiFi AM5 DDR5", 250_000, "Motherboard", "https://t/mb"),
                producto("Procesador Amd Ryzen 9 7900 Am5", 400_000, "CPU", "https://t/cpu"),
                producto("Memoria RAM Corsair Vengeance DDR5 64GB (2x32GB) 6000MHz", 180_000, "RAM", "https://t/ram"),
                producto("Gabinete Corsair 4000D ATX", 90_000, "Gabinete", "https://t/gabinete"),
                producto("Fuente Antec 750W 80 Plus Bronze ATX 3.1", 120_000, "Fuente", "https://t/fuente"),
                producto("SSD Kingston NV2 1TB", 60_000, "Almacenamiento", "https://t/ssd"));
    }

    @Test
    @DisplayName("happy path: returns picks for every slot and a positive totalEstimado")
    void happyPathReturnsPicksAndTotal() throws Exception {
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogoBase()), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode());

        assertThat(result.isError()).isFalse();
        JsonNode json = MAPPER.readTree(result.content());
        assertThat(json.get("picks").size()).isEqualTo(6);
        assertThat(json.get("totalEstimado").asDouble()).isGreaterThan(0);
    }

    @Test
    @DisplayName("conGpu=true adds a gpu pick; conGpu=false never does")
    void conGpuControlsTheGpuSlot() throws Exception {
        List<Product> catalogo = new java.util.ArrayList<>(catalogoBase());
        catalogo.add(producto("Placa de Video RTX 4070", 900_000, "GPU", "https://t/gpu"));
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogo), recommendationService);

        JsonNode conGpu = MAPPER.readTree(
                tool.execute(MAPPER.createObjectNode().put("conGpu", true)).content());
        JsonNode sinGpu = MAPPER.readTree(
                tool.execute(MAPPER.createObjectNode().put("conGpu", false)).content());

        assertThat(streamSlots(conGpu)).contains("gpu");
        assertThat(streamSlots(sinGpu)).doesNotContain("gpu");
    }

    private List<String> streamSlots(JsonNode json) {
        List<String> slots = new java.util.ArrayList<>();
        json.get("picks").forEach(p -> slots.add(p.get("slot").asText()));
        return slots;
    }

    @Test
    @DisplayName("excluir descarta esa url del pick cuando queda otro candidato en el pool")
    void excluirSkipsTheGivenUrlWhenAnotherCandidateRemains() throws Exception {
        List<Product> catalogo = new java.util.ArrayList<>(catalogoBase());
        catalogo.add(producto("Procesador Amd Ryzen 7 7700 Am5", 350_000, "CPU", "https://t/cpu2"));
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogo), recommendationService);

        JsonNode excluir = MAPPER.createArrayNode().add("https://t/cpu");
        ToolResult result = tool.execute(MAPPER.createObjectNode().set("excluir", excluir));

        assertThat(result.isError()).isFalse();
        JsonNode json = MAPPER.readTree(result.content());
        JsonNode cpuPick = pickBySlot(json, "cpu");
        assertThat(cpuPick.get("url").asText()).isEqualTo("https://t/cpu2");
    }

    private JsonNode pickBySlot(JsonNode json, String slot) {
        for (JsonNode p : json.get("picks")) {
            if (slot.equals(p.get("slot").asText())) return p;
        }
        throw new AssertionError("no pick for slot " + slot);
    }

    @Test
    @DisplayName("presupuesto negativo → is_error")
    void negativeBudgetIsError() {
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogoBase()), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("presupuesto", -1));

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("presupuesto no numérico → is_error")
    void nonNumericBudgetIsError() {
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogoBase()), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("presupuesto", "mucho"));

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("sin catálogo cargado → is_error")
    void noSnapshotIsError() {
        CatalogSnapshotPort catalogo = mock(CatalogSnapshotPort.class);
        when(catalogo.getLastResult()).thenReturn(null);
        ProposePcTool tool = new ProposePcTool(catalogo, recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode());

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("gama alta acepta el CPU de gama alta del catálogo base (pc-builder-gama T6)")
    void gamaAltaAcceptsHighTierCpu() throws Exception {
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogoBase()), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("gama", "alta"));

        assertThat(result.isError()).isFalse();
        JsonNode json = MAPPER.readTree(result.content());
        assertThat(streamSlots(json)).contains("cpu");
    }

    @Test
    @DisplayName("gama económica veta el CPU de gama alta, hard filter (pc-builder-gama T6)")
    void gamaEconomicaVetoesHighTierCpu() throws Exception {
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogoBase()), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("gama", "economica"));

        assertThat(result.isError()).isFalse();
        JsonNode json = MAPPER.readTree(result.content());
        assertThat(streamSlots(json)).doesNotContain("cpu");
        boolean cpuSinCompatible = false;
        for (JsonNode n : json.get("sinCompatible")) {
            if ("cpu".equals(n.asText())) cpuSinCompatible = true;
        }
        assertThat(cpuSinCompatible).isTrue();
    }

    @Test
    @DisplayName("gama inválida → is_error (pc-builder-gama T6)")
    void invalidGamaIsError() {
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogoBase()), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("gama", "ultra"));

        assertThat(result.isError()).isTrue();
    }

    // ── uso (pc-builder-homelab T5) ────────────────────────────────────────

    @Test
    @DisplayName("uso=homelab arma sistema y datos en vez de un solo slot de almacenamiento")
    void usoHomelabArmaSistemaYDatos() throws Exception {
        List<Product> catalogo = new java.util.ArrayList<>(catalogoBase());
        catalogo.add(producto("HDD Seagate Ironwolf 4TB NAS", 200_000, "Almacenamiento", "https://t/hdd"));
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogo), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("uso", "homelab"));

        assertThat(result.isError()).isFalse();
        JsonNode json = MAPPER.readTree(result.content());
        assertThat(streamSlots(json)).contains("sistema", "datos").doesNotContain("almacenamiento");
    }

    @Test
    @DisplayName("sin uso (o uso=gaming), sigue armando un solo slot de almacenamiento — default sin cambios")
    void sinUsoSigueArmandoUnSoloSlotDeAlmacenamiento() throws Exception {
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogoBase()), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode());

        assertThat(result.isError()).isFalse();
        JsonNode json = MAPPER.readTree(result.content());
        assertThat(streamSlots(json)).contains("almacenamiento").doesNotContain("sistema", "datos");
    }

    @Test
    @DisplayName("uso inválido → is_error")
    void invalidUsoIsError() {
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogoBase()), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("uso", "servidor"));

        assertThat(result.isError()).isTrue();
    }

    // ── preferencias técnicas (pc-builder-deep-taxonomy T5d) ──────────────

    @Test
    @DisplayName("ddr pedida llega al builder: filtra la mother que no matchea")
    void ddrReachesTheBuilder() throws Exception {
        List<Product> catalogo = new java.util.ArrayList<>(catalogoBase());
        catalogo.add(producto("Motherboard MSI B450M DDR4 AM4", 100_000, "Motherboard", "https://t/ddr4"));
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogo), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("ddr", "ddr4"));

        assertThat(result.isError()).isFalse();
        JsonNode json = MAPPER.readTree(result.content());
        assertThat(pickBySlot(json, "mother").get("url").asText()).isEqualTo("https://t/ddr4");
    }

    @Test
    @DisplayName("ddr inválida → is_error")
    void invalidDdrIsError() {
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogoBase()), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("ddr", "ddr3"));

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("marcaCpu pedida llega al builder")
    void marcaCpuReachesTheBuilder() throws Exception {
        List<Product> catalogo = new java.util.ArrayList<>(catalogoBase());
        catalogo.add(producto("Procesador Intel Core i5 12400F", 150_000, "CPU", "https://t/intel"));
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogo), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("marcaCpu", "intel"));

        assertThat(result.isError()).isFalse();
        JsonNode json = MAPPER.readTree(result.content());
        assertThat(pickBySlot(json, "cpu").get("url").asText()).isEqualTo("https://t/intel");
    }

    @Test
    @DisplayName("marcaCpu inválida → is_error")
    void invalidMarcaCpuIsError() {
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogoBase()), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("marcaCpu", "nvidia"));

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("marcaGpu pedida llega al builder (con conGpu): sin el filtro, la Radeon gana por "
            + "generación (7 > 4) — pedir nvidia tiene que forzar el pick a la RTX igual")
    void marcaGpuReachesTheBuilder() throws Exception {
        List<Product> catalogo = new java.util.ArrayList<>(catalogoBase());
        catalogo.add(producto("Placa de Video Radeon RX 7800 XT", 800_000, "GPU", "https://t/amd-gpu"));
        catalogo.add(producto("Placa de Video RTX 4070", 900_000, "GPU", "https://t/nvidia-gpu"));
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogo), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("conGpu", true).put("marcaGpu", "nvidia"));

        assertThat(result.isError()).isFalse();
        JsonNode json = MAPPER.readTree(result.content());
        assertThat(pickBySlot(json, "gpu").get("url").asText()).isEqualTo("https://t/nvidia-gpu");
    }

    @Test
    @DisplayName("marcaGpu inválida → is_error")
    void invalidMarcaGpuIsError() {
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogoBase()), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("marcaGpu", "intel"));

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("tipoAlmacenamiento pedido llega al builder")
    void tipoAlmacenamientoReachesTheBuilder() throws Exception {
        List<Product> catalogo = new java.util.ArrayList<>(catalogoBase());
        catalogo.add(producto("Disco Rigido Seagate 1TB HDD", 40_000, "Almacenamiento", "https://t/hdd"));
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogo), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("tipoAlmacenamiento", "hdd"));

        assertThat(result.isError()).isFalse();
        JsonNode json = MAPPER.readTree(result.content());
        assertThat(pickBySlot(json, "almacenamiento").get("url").asText()).isEqualTo("https://t/hdd");
    }

    @Test
    @DisplayName("tipoAlmacenamiento inválido → is_error")
    void invalidTipoAlmacenamientoIsError() {
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogoBase()), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("tipoAlmacenamiento", "ssd"));

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("ramDual=true veta el único candidato cuando es un stick simple — el slot pasa "
            + "a sinCompatible, no elige el simple igual")
    void ramDualVetoesASingleStick() throws Exception {
        List<Product> catalogo = new java.util.ArrayList<>(catalogoBase());
        catalogo.removeIf(p -> "RAM".equals(p.categoria()));
        catalogo.add(producto("Memoria RAM Corsair Vengeance DDR5 32GB 6000MHz", 90_000, "RAM", "https://t/single"));
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogo), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("ramDual", true));

        assertThat(result.isError()).isFalse();
        JsonNode json = MAPPER.readTree(result.content());
        assertThat(streamSlots(json)).doesNotContain("ram");
        assertThat(sinCompatibleContains(json, "ram")).isTrue();
    }

    @Test
    @DisplayName("wifi=true veta el único candidato cuando no dice wifi — el slot pasa a "
            + "sinCompatible, no elige la mother sin wifi igual")
    void wifiVetoesAMotherboardWithoutWifi() throws Exception {
        List<Product> catalogo = new java.util.ArrayList<>(catalogoBase());
        catalogo.removeIf(p -> "Motherboard".equals(p.categoria()));
        catalogo.add(producto("Motherboard ASUS TUF Gaming B850M-E AM5 DDR5", 200_000, "Motherboard",
                "https://t/nowifi"));
        ProposePcTool tool = new ProposePcTool(catalogoCon(catalogo), recommendationService);

        ToolResult result = tool.execute(MAPPER.createObjectNode().put("wifi", true));

        assertThat(result.isError()).isFalse();
        JsonNode json = MAPPER.readTree(result.content());
        assertThat(streamSlots(json)).doesNotContain("mother");
        assertThat(sinCompatibleContains(json, "mother")).isTrue();
    }

    private boolean sinCompatibleContains(JsonNode json, String slot) {
        for (JsonNode n : json.get("sinCompatible")) {
            if (slot.equals(n.asText())) return true;
        }
        return false;
    }
}
