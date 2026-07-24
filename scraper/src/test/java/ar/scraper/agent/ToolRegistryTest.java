package ar.scraper.agent;

import ar.scraper.aggregator.normalize.CategoryGroups;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RED→GREEN coverage for {@link ToolRegistry} (llm-catalog-nlp, task 4.1/4.2):
 * exactly 3 tools, and the {@code categoria} tool-schema enum matches
 * {@link CategoryGroups#canonicalCategories()} exactly (Safeguard A — the
 * model cannot invent a nonexistent category).
 */
@Epic("LLM Catalog Agent")
@Feature("ToolRegistry")
@Story("Exactly 3 read-only tools; categoria enum == canonical taxonomy")
@DisplayName("ToolRegistry")
class ToolRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("exposes exactly 3 tools: search_products, view_product, propose_reclassify")
    void exposesExactlyThreeTools() {
        ScraperService service = mock(ScraperService.class);
        ToolRegistry registry = new ToolRegistry(
                new SearchProductsTool(service), new ViewProductTool(service), new ProposeReclassifyTool(service));

        List<ToolSpec> specs = registry.specs();

        assertThat(specs).hasSize(3);
        assertThat(specs.stream().map(ToolSpec::name).toList())
                .containsExactlyInAnyOrder(SearchProductsTool.NAME, ViewProductTool.NAME, ProposeReclassifyTool.NAME);
    }

    @Test
    @DisplayName("propose_reclassify's categoria schema enum == CategoryGroups.canonicalCategories()")
    void categoriaEnumMatchesCanonicalTaxonomy() {
        ScraperService service = mock(ScraperService.class);
        ToolRegistry registry = new ToolRegistry(
                new SearchProductsTool(service), new ViewProductTool(service), new ProposeReclassifyTool(service));

        ToolSpec proposeSpec = registry.specs().stream()
                .filter(s -> s.name().equals(ProposeReclassifyTool.NAME))
                .findFirst().orElseThrow();

        JsonNode enumNode = proposeSpec.paramsSchema().path("properties").path("categoria").path("enum");
        List<String> enumValues = new ArrayList<>();
        enumNode.forEach(n -> enumValues.add(n.asText()));

        assertThat(Set.copyOf(enumValues)).isEqualTo(CategoryGroups.canonicalCategories());
    }

    @Test
    @DisplayName("execute() routes to the matching tool by name and fills in the real toolCallId")
    void executeRoutesByNameAndFillsCallId() {
        ScraperService service = mock(ScraperService.class);
        ToolRegistry registry = new ToolRegistry(
                new SearchProductsTool(service), new ViewProductTool(service), new ProposeReclassifyTool(service));

        JsonNode args = MAPPER.createObjectNode().put("query", "remera");
        ToolCall call = new ToolCall("call_1", SearchProductsTool.NAME, args);

        ToolResult result = registry.execute(call);

        assertThat(result.toolCallId()).isEqualTo("call_1");
        assertThat(result.isError()).isTrue(); // no catalog data mocked — still proves routing + id fill-in
    }

    @Test
    @DisplayName("execute() with an unknown tool name → is_error, no crash")
    void executeUnknownToolNameIsError() {
        ScraperService service = mock(ScraperService.class);
        ToolRegistry registry = new ToolRegistry(
                new SearchProductsTool(service), new ViewProductTool(service), new ProposeReclassifyTool(service));

        ToolCall call = new ToolCall("call_9", "delete_everything", MAPPER.createObjectNode());

        ToolResult result = registry.execute(call);

        assertThat(result.isError()).isTrue();
        assertThat(result.toolCallId()).isEqualTo("call_9");
    }
}
