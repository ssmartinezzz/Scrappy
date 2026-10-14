package ar.scraper.agent;

import ar.scraper.aggregator.CatalogSnapshotPort;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.Facets;
import ar.scraper.model.Product;
import ar.scraper.outfits.RecommendationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ProposePcTool — parámetro modo")
class ProposePcToolModoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String A620 = "https://t/a620";
    private static final String X870 = "https://t/x870";

    private static Product producto(String nombre, double precio, String categoria, String url) {
        return Product.builder()
                .sitio("TestSitio").nombre(nombre).precio(precio).url(url)
                .imagenUrl("https://img/test.jpg").categoria(categoria).genero("")
                .talles(List.of()).ml(Product.MlScore.EMPTY).marca("").rubro("tecnologia").gymrat(false)
                .build();
    }

    /** Same fixture as PcBuilderGastarTodoTest: with 1M, Ahorrar keeps the A620 and Gastar takes the X870. */
    private ProposePcTool tool() {
        List<Product> productos = List.of(
                producto("Motherboard Gigabyte A620M H DDR5 AM5", 80_000, "Motherboard", A620),
                producto("Motherboard Asus Rog Strix X870-E Gaming DDR5 AM5", 200_000, "Motherboard", X870),
                producto("Procesador Amd Ryzen 9 9950X3D", 280_000, "CPU", "https://t/9950x3d"));
        CatalogSnapshotPort catalogo = mock(CatalogSnapshotPort.class);
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        when(catalogo.getLastResult()).thenReturn(new AggregatedResult(productos, Map.of(), Map.of(), facets, 0, 0));
        return new ProposePcTool(catalogo, new RecommendationService());
    }

    private String mother(ToolResult result) throws Exception {
        assertThat(result.isError()).isFalse();
        for (JsonNode p : MAPPER.readTree(result.content()).get("picks")) {
            if (p.get("slot").asText().equals("mother")) return p.get("url").asText();
        }
        return "";
    }

    private JsonNode args(String modo) {
        var n = MAPPER.createObjectNode().put("presupuesto", 1_000_000).put("gama", "alta");
        if (modo != null) n.put("modo", modo);
        return n;
    }

    @Test
    @DisplayName("a modo outside ahorrar/gastar is an error")
    void modoInvalidoEsError() {
        ToolResult result = tool().execute(args("derrochar"));

        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("'modo' tiene que ser 'ahorrar' o 'gastar'");
    }

    @Test
    @DisplayName("modo='gastar' reaches the builder: the leftover upgrades the mother")
    void gastarLlegaAlBuilder() throws Exception {
        assertThat(mother(tool().execute(args("gastar")))).isEqualTo(X870);
    }

    @Test
    @DisplayName("modo absent or 'ahorrar' keeps the cheapest compatible board")
    void ahorrarEsElDefault() throws Exception {
        assertThat(mother(tool().execute(args(null)))).isEqualTo(A620);
        assertThat(mother(tool().execute(args("ahorrar")))).isEqualTo(A620);
    }

    @Test
    @DisplayName("the schema exposes modo with the ahorrar/gastar enum")
    void elSchemaExponeElEnum() {
        JsonNode modo = tool().spec().paramsSchema().path("properties").path("modo");

        assertThat(modo.path("type").asText()).isEqualTo("string");
        assertThat(modo.path("enum")).extracting(JsonNode::asText).containsExactly("ahorrar", "gastar");
    }
}
