package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RED→GREEN coverage for {@link SearchProductsTool} (llm-catalog-nlp, task
 * 3.1) — the search tool MUST query real {@code productos} rows via
 * {@link ScraperService#getLastResult()}, never fabricate data.
 */
@Epic("LLM Catalog Agent")
@Feature("Catalog tools")
@Story("search_products — real matches only")
@DisplayName("SearchProductsTool")
class SearchProductsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("returns real productos matches by name (accent/case-insensitive), not fabricated data")
    void returnsRealMatchesByName() throws Exception {
        ScraperService service = mock(ScraperService.class);
        Product remera = producto("https://a.com/1", "Remera SAD Adidas", "Zapatilla Running", "Adidas");
        Product buzo   = producto("https://a.com/2", "Buzo Nike Canguro", "Buzo", "Nike");
        when(service.getLastResult()).thenReturn(mockResult(List.of(remera, buzo)));

        SearchProductsTool tool = new SearchProductsTool(service);
        JsonNode args = MAPPER.createObjectNode().put("query", "remera");
        ToolResult result = tool.execute(args);

        assertThat(result.isError()).isFalse();
        JsonNode matches = MAPPER.readTree(result.content());
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).get("url").asText()).isEqualTo("https://a.com/1");
        assertThat(matches.get(0).get("nombre").asText()).isEqualTo("Remera SAD Adidas");
    }

    @Test
    @DisplayName("matches are accent-insensitive")
    void matchesAreAccentInsensitive() throws Exception {
        ScraperService service = mock(ScraperService.class);
        Product pantalon = producto("https://a.com/3", "Pantalón Cargo", "Pantalón", "Levi's");
        when(service.getLastResult()).thenReturn(mockResult(List.of(pantalon)));

        SearchProductsTool tool = new SearchProductsTool(service);
        JsonNode args = MAPPER.createObjectNode().put("query", "pantalon"); // sin tilde
        ToolResult result = tool.execute(args);

        JsonNode matches = MAPPER.readTree(result.content());
        assertThat(matches).hasSize(1);
    }

    @Test
    @DisplayName("respects the limit parameter")
    void respectsLimitParameter() throws Exception {
        ScraperService service = mock(ScraperService.class);
        List<Product> many = List.of(
                producto("https://a.com/1", "Remera A", "Remera", "M1"),
                producto("https://a.com/2", "Remera B", "Remera", "M2"),
                producto("https://a.com/3", "Remera C", "Remera", "M3"));
        when(service.getLastResult()).thenReturn(mockResult(many));

        SearchProductsTool tool = new SearchProductsTool(service);
        JsonNode args = MAPPER.createObjectNode().put("query", "remera").put("limit", 2);
        ToolResult result = tool.execute(args);

        JsonNode matches = MAPPER.readTree(result.content());
        assertThat(matches).hasSize(2);
    }

    @Test
    @DisplayName("empty query → is_error, no crash")
    void emptyQueryIsError() {
        ScraperService service = mock(ScraperService.class);
        SearchProductsTool tool = new SearchProductsTool(service);
        JsonNode args = MAPPER.createObjectNode().put("query", "");

        ToolResult result = tool.execute(args);

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("no catalog data yet → is_error, not a fabricated empty match")
    void noCatalogDataIsError() {
        ScraperService service = mock(ScraperService.class);
        when(service.getLastResult()).thenReturn(null);
        SearchProductsTool tool = new SearchProductsTool(service);
        JsonNode args = MAPPER.createObjectNode().put("query", "remera");

        ToolResult result = tool.execute(args);

        assertThat(result.isError()).isTrue();
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
