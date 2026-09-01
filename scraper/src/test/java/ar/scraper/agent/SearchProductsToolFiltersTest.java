package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.ResultAggregator.Facets;
import ar.scraper.model.Product;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Structured filters on {@code search_products}.
 *
 * <p>The tool only ever took a free-text {@code query} matched as a substring over
 * {@code nombre}/{@code marca}. That made a whole class of ordinary request
 * unanswerable — "musculosas que no sean de fútbol y por menos de $50.000" — for two
 * separate reasons:</p>
 *
 * <ul>
 *   <li>a CATEGORY is not a word in the name. A product classified {@code Musculosa}
 *       and named "Remera sin mangas Dry Fit" was invisible to {@code query=musculosa},
 *       which is exactly the product most worth reviewing;</li>
 *   <li>substring matching has no way to express "not", or a price ceiling.</li>
 * </ul>
 *
 * <p>The model would answer such a question by filtering in prose over whatever the
 * unfiltered search happened to return — the grounding gate cannot catch that, because
 * a real tool call did run.</p>
 */
@Epic("LLM Catalog Agent")
@Feature("Catalog tools")
@Story("search_products — structured filters")
@DisplayName("SearchProductsTool — filters")
class SearchProductsToolFiltersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SearchProductsTool toolCon(List<Product> catalogo) {
        ScraperService service = mock(ScraperService.class);
        var facets = new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        when(service.getLastResult())
                .thenReturn(new AggregatedResult(catalogo, Map.of(), Map.of(), facets, 0, 0));
        return new SearchProductsTool(service);
    }

    private Product producto(String url, String nombre, String categoria, double precio, String genero) {
        return new Product("Sitio", nombre, precio, null, url, "img",
                categoria, genero, List.of(), Product.MlScore.EMPTY, "Marca",
                "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY);
    }

    private List<String> urls(ToolResult r) throws Exception {
        JsonNode arr = MAPPER.readTree(r.content());
        return arr.findValuesAsText("url");
    }

    /** El catálogo del ejemplo del usuario. */
    private List<Product> catalogo() {
        return List.of(
                // Clasificada Musculosa pero SIN la palabra en el nombre: el caso que
                // una búsqueda por texto no puede alcanzar.
                producto("https://t.com/1", "Remera sin mangas Dry Fit", "Musculosa", 32000, "hombre"),
                producto("https://t.com/2", "Musculosa Training Negra",  "Musculosa", 41000, "hombre"),
                producto("https://t.com/3", "Musculosa de Fútbol Selección", "Musculosa", 38000, "hombre"),
                producto("https://t.com/4", "Musculosa Oversize Premium", "Musculosa", 72000, "mujer"),
                producto("https://t.com/5", "Remera Manga Corta",        "Remera",    25000, "hombre"));
    }

    @Test
    @DisplayName("categoria filters on the classified category, not on words in the name")
    void filtraPorCategoria() throws Exception {
        ObjectNode args = MAPPER.createObjectNode().put("categoria", "Musculosa");

        ToolResult r = toolCon(catalogo()).execute(args);

        assertThat(r.isError()).isFalse();
        assertThat(urls(r))
                .as("incluye la que NO dice 'musculosa' en el nombre, y excluye la Remera")
                .containsExactlyInAnyOrder("https://t.com/1", "https://t.com/2",
                        "https://t.com/3", "https://t.com/4");
    }

    @Test
    @DisplayName("excluir drops matches by term, accent- and case-insensitively")
    void excluyeTerminos() throws Exception {
        ObjectNode args = MAPPER.createObjectNode().put("categoria", "Musculosa");
        args.putArray("excluir").add("futbol"); // sin tilde: tiene que atrapar "Fútbol"

        ToolResult r = toolCon(catalogo()).execute(args);

        assertThat(urls(r)).doesNotContain("https://t.com/3").hasSize(3);
    }

    @Test
    @DisplayName("precioMax and precioMin bound the results")
    void filtraPorPrecio() throws Exception {
        ObjectNode args = MAPPER.createObjectNode().put("categoria", "Musculosa");
        args.put("precioMax", 50000);

        ToolResult r = toolCon(catalogo()).execute(args);

        assertThat(urls(r)).doesNotContain("https://t.com/4").hasSize(3);

        ObjectNode conMin = MAPPER.createObjectNode().put("categoria", "Musculosa");
        conMin.put("precioMin", 40000);
        assertThat(urls(toolCon(catalogo()).execute(conMin)))
                .containsExactlyInAnyOrder("https://t.com/2", "https://t.com/4");
    }

    @Test
    @DisplayName("the user's actual question: musculosas, not football, under $50.000")
    void elEjemploDelUsuario() throws Exception {
        ObjectNode args = MAPPER.createObjectNode().put("categoria", "Musculosa");
        args.putArray("excluir").add("futbol");
        args.put("precioMax", 50000);

        ToolResult r = toolCon(catalogo()).execute(args);

        assertThat(urls(r)).containsExactlyInAnyOrder("https://t.com/1", "https://t.com/2");
    }

    @Test
    @DisplayName("genero filters too, and combines with the rest")
    void filtraPorGenero() throws Exception {
        ObjectNode args = MAPPER.createObjectNode().put("categoria", "Musculosa");
        args.put("genero", "mujer");

        assertThat(urls(toolCon(catalogo()).execute(args)))
                .containsExactly("https://t.com/4");
    }

    @Test
    @DisplayName("a call with no criteria at all is an error, not the whole catalog")
    void sinCriteriosEsError() {
        // Devolver los primeros N del catálogo ante una llamada vacía sería peor que un
        // error: el modelo recibe datos reales y responde con seguridad sobre una muestra
        // arbitraria que nadie pidió, y la barrera de grounding lo da por fundamentado.
        ToolResult r = toolCon(catalogo()).execute(MAPPER.createObjectNode());

        assertThat(r.isError()).isTrue();
    }

    @Test
    @DisplayName("query still works alone, and still matches nombre and marca")
    void elQueryDeTextoSigueAndando() throws Exception {
        ObjectNode args = MAPPER.createObjectNode().put("query", "dry fit");

        assertThat(urls(toolCon(catalogo()).execute(args))).containsExactly("https://t.com/1");
    }

    @Test
    @DisplayName("results carry genero so the model can report what it filtered on")
    void elResultadoIncluyeGenero() throws Exception {
        ObjectNode args = MAPPER.createObjectNode().put("categoria", "Remera");

        JsonNode arr = MAPPER.readTree(toolCon(catalogo()).execute(args).content());
        assertThat(arr.get(0).has("genero")).isTrue();
        assertThat(arr.get(0).get("genero").asText()).isEqualTo("hombre");
    }
}
