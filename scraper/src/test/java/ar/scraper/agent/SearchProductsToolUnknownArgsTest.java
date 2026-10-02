package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.Facets;
import ar.scraper.model.Product;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SearchProductsTool — unknown arguments")
class SearchProductsToolUnknownArgsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SearchProductsTool tool() {
        ScraperService service = mock(ScraperService.class);
        Product p = Product.builder()
                .sitio("Sitio")
                .nombre("Zapatilla Nike Air")
                .precio(1000)
                .precioOriginal(null)
                .url("https://a.com/1")
                .imagenUrl("img")
                .categoria("Zapatilla")
                .genero("hombre")
                .talles(List.of())
                .ml(Product.MlScore.EMPTY)
                .marca("Nike")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY)
                .build();
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        when(service.getLastResult()).thenReturn(new AggregatedResult(List.of(p), Map.of(), Map.of(), facets, 0, 0));
        return new SearchProductsTool(service);
    }

    @Test
    @DisplayName("an invented key is an error naming it, listing the valid keys and pointing brand/model to 'query'")
    void unknownKeyIsRejected() throws Exception {
        JsonNode args = MAPPER.readTree("{\"query\":\"zapatillas\",\"marca\":\"Nike\"}");

        ToolResult result = tool().execute(args);

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("marca");
        for (String valid : List.of("query", "categoria", "genero", "excluir", "precioMin", "precioMax",
                "enOferta", "limit")) {
            assertThat(result.content()).contains(valid);
        }
        assertThat(result.content()).contains("'query'").containsIgnoringCase("marca");
    }

    @Test
    @DisplayName("every unknown key is named, and the check runs before any filtering (even with no valid criterion)")
    void allUnknownKeysNamedBeforeFiltering() throws Exception {
        JsonNode args = MAPPER.readTree("{\"ddr\":\"5\",\"precioMaximo\":200000}");

        ToolResult result = tool().execute(args);

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("ddr").contains("precioMaximo");
        assertThat(result.content()).doesNotContain("Hace falta al menos un criterio");
    }

    @Test
    @DisplayName("only declared keys still search normally")
    void declaredKeysStillWork() throws Exception {
        JsonNode args = MAPPER.readTree("{\"query\":\"zapatilla\",\"limit\":5,\"enOferta\":false}");

        ToolResult result = tool().execute(args);

        assertThat(result.isError()).isFalse();
    }

    @Test
    @DisplayName("the declared keys in the error are exactly the schema's properties")
    void validKeysMatchTheSchema() {
        JsonNode props = tool().spec().paramsSchema().path("properties");
        props.fieldNames().forEachRemaining(name -> {
            ToolResult r = tool().execute(MAPPER.createObjectNode().put("zzz", 1));
            assertThat(r.content()).contains(name);
        });
    }
}
