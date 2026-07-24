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
 * RED→GREEN coverage for {@link ViewProductTool} (llm-catalog-nlp, task 3.3).
 */
@Epic("LLM Catalog Agent")
@Feature("Catalog tools")
@Story("view_product — current classification")
@DisplayName("ViewProductTool")
class ViewProductToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("returns the product's current categoria/subCategoria/genero/marca")
    void returnsCurrentClassification() throws Exception {
        ScraperService service = mock(ScraperService.class);
        Product p = new Product("Sitio", "Remera SAD Adidas", 1000, null, "https://a.com/1", "img",
                "Zapatilla Running", "hombre", List.of(), Product.MlScore.EMPTY, "Adidas",
                "indumentaria", false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY,
                1, "running");
        when(service.getLastResult()).thenReturn(mockResult(List.of(p)));

        ViewProductTool tool = new ViewProductTool(service);
        JsonNode args = MAPPER.createObjectNode().put("url", "https://a.com/1");
        ToolResult result = tool.execute(args);

        assertThat(result.isError()).isFalse();
        JsonNode node = MAPPER.readTree(result.content());
        assertThat(node.get("categoria").asText()).isEqualTo("Zapatilla Running");
        assertThat(node.get("subCategoria").asText()).isEqualTo("running");
        assertThat(node.get("genero").asText()).isEqualTo("hombre");
        assertThat(node.get("marca").asText()).isEqualTo("Adidas");
    }

    @Test
    @DisplayName("unknown url → is_error")
    void unknownUrlIsError() {
        ScraperService service = mock(ScraperService.class);
        when(service.getLastResult()).thenReturn(mockResult(List.of()));

        ViewProductTool tool = new ViewProductTool(service);
        JsonNode args = MAPPER.createObjectNode().put("url", "https://nope.com/x");
        ToolResult result = tool.execute(args);

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("missing url param → is_error, no crash")
    void missingUrlIsError() {
        ScraperService service = mock(ScraperService.class);
        ViewProductTool tool = new ViewProductTool(service);
        JsonNode args = MAPPER.createObjectNode();

        ToolResult result = tool.execute(args);

        assertThat(result.isError()).isTrue();
    }

    private AggregatedResult mockResult(List<Product> products) {
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        return new AggregatedResult(products, Map.of(), Map.of(), facets, 0, 0);
    }
}
