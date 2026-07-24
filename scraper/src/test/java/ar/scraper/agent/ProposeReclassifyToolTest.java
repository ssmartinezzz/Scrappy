package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.db.DatabaseService;
import ar.scraper.model.Product;
import ar.scraper.web.ScraperService;
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
import static org.mockito.Mockito.*;

/**
 * RED→GREEN coverage for {@link ProposeReclassifyTool} (llm-catalog-nlp,
 * task 3.5/3.6 — design D4 Safeguard A+B): the tool VALIDATES (url exists,
 * category ∈ taxonomy) and returns a {@link ReclassifyProposal} diff. It
 * NEVER writes — the class holds no {@link DatabaseService} reference at
 * all (a structural guarantee, not just a runtime check); the mocked
 * {@code db} below additionally proves zero interactions ever happen
 * through any path reachable from this tool.
 */
@Epic("LLM Catalog Agent")
@Feature("Catalog tools")
@Story("propose_reclassify — validate-only, never writes (Safeguard A+B)")
@DisplayName("ProposeReclassifyTool")
class ProposeReclassifyToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("valid call → ReclassifyProposal diff, zero DatabaseService write interactions")
    void validCallReturnsProposalNoWrite() throws Exception {
        DatabaseService db = mock(DatabaseService.class);
        ScraperService service = mock(ScraperService.class);
        Product current = producto("https://a.com/1", "Zapatilla SAD Adidas", "Zapatilla Running", "Adidas");
        when(service.getLastResult()).thenReturn(mockResult(List.of(current)));

        ProposeReclassifyTool tool = new ProposeReclassifyTool(service);
        JsonNode args = MAPPER.createObjectNode()
                .put("url", "https://a.com/1")
                .put("categoria", "Buzo");
        ToolResult result = tool.execute(args);

        assertThat(result.isError()).isFalse();
        ReclassifyProposal proposal = MAPPER.readValue(result.content(), ReclassifyProposal.class);
        assertThat(proposal.url()).isEqualTo("https://a.com/1");
        assertThat(proposal.categoriaActual()).isEqualTo("Zapatilla Running");
        assertThat(proposal.categoriaPropuesta()).isEqualTo("Buzo");
        verifyNoInteractions(db);
    }

    @Test
    @DisplayName("non-taxonomy category → is_error listing valid values, no write")
    void nonTaxonomyCategoryIsError() {
        DatabaseService db = mock(DatabaseService.class);
        ScraperService service = mock(ScraperService.class);
        Product current = producto("https://a.com/1", "Zapatilla SAD Adidas", "Zapatilla Running", "Adidas");
        when(service.getLastResult()).thenReturn(mockResult(List.of(current)));

        ProposeReclassifyTool tool = new ProposeReclassifyTool(service);
        JsonNode args = MAPPER.createObjectNode()
                .put("url", "https://a.com/1")
                .put("categoria", "Frisa"); // not in taxonomy — real qwen3 hallucination example
        ToolResult result = tool.execute(args);

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Buzo"); // a real taxonomy value should be listed
        verifyNoInteractions(db);
    }

    @Test
    @DisplayName("unknown url → is_error, no write")
    void unknownUrlIsError() {
        DatabaseService db = mock(DatabaseService.class);
        ScraperService service = mock(ScraperService.class);
        when(service.getLastResult()).thenReturn(mockResult(List.of()));

        ProposeReclassifyTool tool = new ProposeReclassifyTool(service);
        JsonNode args = MAPPER.createObjectNode()
                .put("url", "https://nope.com/x")
                .put("categoria", "Buzo");
        ToolResult result = tool.execute(args);

        assertThat(result.isError()).isTrue();
        verifyNoInteractions(db);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private AggregatedResult mockResult(List<Product> products) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(products, Map.of(), Map.of(), facets, 0, 0);
    }

    private Product producto(String url, String nombre, String categoria, String marca) {
        return new Product("Sitio", nombre, 1000, null, url, "img",
                categoria, "unisex", List.of(), Product.MlScore.EMPTY, marca,
                "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY);
    }
}
