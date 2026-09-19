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
}
