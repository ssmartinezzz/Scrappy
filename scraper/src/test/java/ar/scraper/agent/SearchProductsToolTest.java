package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.Facets;
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

    // ── T1: token search ────────────────────────────────────────────────

    @Test
    @DisplayName("query tokens match in any order, each in nombre OR marca (not one contiguous substring)")
    void queryTokensMatchInAnyOrder() throws Exception {
        SearchProductsTool tool = toolCon(List.of(
                disco("https://a.com/1", "HD SSD 1TB HIKSEMI WAVE SATA III 2.5\"", "Hiksemi", 100, null),
                disco("https://a.com/2", "Auricular Gamer", "Hiksemi", 100, null)));

        JsonNode matches = search(tool, MAPPER.createObjectNode().put("query", "sata ssd hiksemi"));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).get("url").asText()).isEqualTo("https://a.com/1");
    }

    @Test
    @DisplayName("a number spaced from its unit matches the glued form both ways (1 tb == 1tb)")
    void numberUnitSpacingIsEquivalent() throws Exception {
        SearchProductsTool tool = toolCon(List.of(
                disco("https://a.com/1", "SSD 1TB Kingston", "Kingston", 100, null),
                disco("https://a.com/2", "SSD 500 GB Kingston", "Kingston", 100, null)));

        assertThat(search(tool, MAPPER.createObjectNode().put("query", "ssd 1 tb"))).hasSize(1);
        assertThat(search(tool, MAPPER.createObjectNode().put("query", "ssd 500gb"))).hasSize(1);
        assertThat(search(tool, MAPPER.createObjectNode().put("query", "ssd 500 gb"))).hasSize(1);
    }

    // ── T2: discounts ───────────────────────────────────────────────────

    @Test
    @DisplayName("results carry precioOrig, descuentoPct and subCategoria; both absent without a real discount")
    void resultsCarryDiscountAndSubCategoria() throws Exception {
        SearchProductsTool tool = toolCon(List.of(
                disco("https://a.com/1", "SSD Uno", "M", 75, 100.0),
                disco("https://a.com/2", "SSD Dos", "M", 100, null),
                disco("https://a.com/3", "SSD Tres", "M", 120, 100.0)));

        JsonNode matches = search(tool, MAPPER.createObjectNode().put("query", "ssd"));

        assertThat(matches.get(0).get("precioOrig").asDouble()).isEqualTo(100.0);
        assertThat(matches.get(0).get("descuentoPct").asInt()).isEqualTo(25);
        assertThat(matches.get(0).get("subCategoria").asText()).isEqualTo("Sub");
        assertThat(matches.get(1).has("precioOrig")).isFalse();
        assertThat(matches.get(1).has("descuentoPct")).isFalse();
        assertThat(matches.get(2).has("descuentoPct")).isFalse(); // precioOrig <= precio is no discount
    }

    @Test
    @DisplayName("enOferta keeps only precioOrig > precio and sorts by discount desc BEFORE the limit")
    void enOfertaFiltersAndSortsBeforeLimit() throws Exception {
        SearchProductsTool tool = toolCon(List.of(
                disco("https://a.com/1", "SSD chico", "M", 90, 100.0),
                disco("https://a.com/2", "SSD sin oferta", "M", 50, null),
                disco("https://a.com/3", "SSD grande", "M", 50, 100.0),
                disco("https://a.com/4", "SSD medio", "M", 80, 100.0)));

        JsonNode matches = search(tool,
                MAPPER.createObjectNode().put("query", "ssd").put("enOferta", true).put("limit", 2));

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).get("url").asText()).isEqualTo("https://a.com/3");
        assertThat(matches.get(1).get("url").asText()).isEqualTo("https://a.com/4");
    }

    @Test
    @DisplayName("enOferta: equal rounded discount ties break by relevance, not by float noise")
    void enOfertaTiesOnRoundedPctBreakByRelevance() throws Exception {
        // Sitios que suben 10% el precio de lista: el descuento real varía en la cuarta cifra decimal.
        SearchProductsTool tool = toolCon(List.of(
                disco("https://a.com/parcial", "SSD 960GB SATA III 2.5 pulgadas simil 1TB", "M", 90.908, 100.0),
                disco("https://a.com/exacto", "SSD 1TB SATA", "M", 90.909, 100.0)));

        JsonNode matches = search(tool,
                MAPPER.createObjectNode().put("query", "ssd sata 1tb").put("enOferta", true));

        assertThat(matches.get(0).get("url").asText()).isEqualTo("https://a.com/exacto");
    }

    @Test
    @DisplayName("enOferta on its own is not a search criterion (it would be the whole discounted catalog)")
    void enOfertaAloneIsNotACriterion() {
        SearchProductsTool tool = toolCon(List.of(disco("https://a.com/1", "SSD", "M", 50, 100.0)));

        assertThat(tool.execute(MAPPER.createObjectNode().put("enOferta", true)).isError()).isTrue();
    }

    @Test
    @DisplayName("no match with enOferta still returns a JSON empty array (the loop's no-matches signal)")
    void emptyResultStaysAnEmptyArray() throws Exception {
        SearchProductsTool tool = toolCon(List.of(disco("https://a.com/1", "SSD", "M", 50, null)));

        JsonNode matches = search(tool, MAPPER.createObjectNode().put("query", "ssd").put("enOferta", true));

        assertThat(matches.isArray()).isTrue();
        assertThat(matches).isEmpty();
    }

    @Test
    @DisplayName("acceptance: 'ssd sata 1 tb' + enOferta returns only the discounted 1TB SATA SSD; "
            + "'ssd sata' + enOferta returns both discounted ones, biggest discount first")
    void ssdSataDiscountAcceptance() throws Exception {
        SearchProductsTool tool = toolCon(List.of(
                disco("https://a.com/wd", "HD SSD 2TB WD BLUE SA510 SATA III 2.5\"", "WD", 469065.83, 515972.38),
                disco("https://a.com/hik", "HD SSD 1TB HIKSEMI WAVE SATA III 2.5\"", "Hiksemi", 264399.98, 290839.93),
                disco("https://a.com/sin", "HD SSD 1TB KINGSTON A400 SATA III 2.5\"", "Kingston", 250000, null),
                disco("https://a.com/nvme", "SSD 1TB NVMe M.2 KINGSTON NV2", "Kingston", 200000, 300000.0)));

        JsonNode uno = search(tool,
                MAPPER.createObjectNode().put("query", "ssd sata 1 tb").put("enOferta", true));
        assertThat(uno).hasSize(1);
        assertThat(uno.get(0).get("url").asText()).isEqualTo("https://a.com/hik");

        JsonNode ambos = search(tool,
                MAPPER.createObjectNode().put("query", "ssd sata").put("enOferta", true));
        assertThat(ambos).hasSize(2);
        // Hiksemi 9.09% vs WD 9.09%: ties are not the point, both must be present and ordered by pct desc
        assertThat(ambos.get(0).get("descuentoPct").asInt())
                .isGreaterThanOrEqualTo(ambos.get(1).get("descuentoPct").asInt());
        assertThat(List.of(ambos.get(0).get("url").asText(), ambos.get(1).get("url").asText()))
                .containsExactlyInAnyOrder("https://a.com/hik", "https://a.com/wd");
    }

    // ── T6-T8: stopwords, stemming, typos, relevance, relaxed ───────────

    @Test
    @DisplayName("conversational SSD question as a raw query + enOferta returns the discounted SATA SSDs, no partial flag")
    void conversationalQueryFindsDiscountedSata() throws Exception {
        SearchProductsTool tool = toolCon(List.of(
                disco("https://a.com/wd", "HD SSD 2TB WD BLUE SA510 SATA III 2.5\"", "WD", 469065.83, 515972.38),
                disco("https://a.com/hik", "HD SSD 1TB HIKSEMI WAVE SATA III 2.5\"", "Hiksemi", 264399.98, 290839.93),
                disco("https://a.com/sin", "HD SSD 1TB KINGSTON A400 SATA III 2.5\"", "Kingston", 250000, null),
                disco("https://a.com/nvme", "SSD 1TB NVMe M.2 KINGSTON NV2", "Kingston", 200000, 300000.0)));

        JsonNode matches = search(tool, MAPPER.createObjectNode()
                .put("query", "tenés algún ssd sata de tb en descuento").put("enOferta", true));

        assertThat(matches).hasSize(2);
        assertThat(matches.findValuesAsText("url")).containsExactlyInAnyOrder("https://a.com/hik", "https://a.com/wd");
        assertThat(matches.get(0).has("coincidencia")).isFalse();
    }

    @Test
    @DisplayName("a plural in the query finds the singular in the name (zapatillas -> Zapatilla)")
    void pluralFindsSingular() throws Exception {
        SearchProductsTool tool = toolCon(List.of(
                disco("https://a.com/1", "Zapatilla Urbana Blanca", "Topper", 100, null),
                disco("https://a.com/2", "Buzo Canguro", "Topper", 100, null)));

        JsonNode matches = search(tool, MAPPER.createObjectNode().put("query", "zapatillas"));

        assertThat(matches.findValuesAsText("url")).containsExactly("https://a.com/1");
    }

    @Test
    @DisplayName("a typo in the brand still finds it (kingstom -> Kingston)")
    void typoFindsBrand() throws Exception {
        SearchProductsTool tool = toolCon(List.of(
                disco("https://a.com/1", "SSD 480GB A400", "Kingston", 100, null),
                disco("https://a.com/2", "SSD 480GB Blue", "WD", 100, null)));

        JsonNode matches = search(tool, MAPPER.createObjectNode().put("query", "kingstom"));

        assertThat(matches.findValuesAsText("url")).containsExactly("https://a.com/1");
    }

    @Test
    @DisplayName("'ram' does not match inside 'Programa' or 'Frambuesa' (the space is the word boundary)")
    void shortTokenDoesNotEatOtherWords() throws Exception {
        SearchProductsTool tool = toolCon(List.of(
                disco("https://a.com/1", "Programa Nutricional Whey", "Marca", 100, null),
                disco("https://a.com/2", "Frambuesa Barra Proteica", "Marca", 100, null),
                disco("https://a.com/3", "Memoria RAM DDR4 8GB", "Marca", 100, null)));

        JsonNode matches = search(tool, MAPPER.createObjectNode().put("query", "ram"));

        assertThat(matches.findValuesAsText("url")).containsExactly("https://a.com/3");
    }

    @Test
    @DisplayName("a query made only of filler words is an error that says so; with another criterion it is ignored")
    void fillerOnlyQuery() throws Exception {
        SearchProductsTool tool = toolCon(List.of(disco("https://a.com/1", "SSD", "M", 100, null)));

        ToolResult r = tool.execute(MAPPER.createObjectNode().put("query", "tenés algún de la"));
        assertThat(r.isError()).isTrue();
        assertThat(r.content()).contains("palabras vacías");

        JsonNode conCategoria = search(tool,
                MAPPER.createObjectNode().put("query", "tenés algún").put("categoria", "Almacenamiento"));
        assertThat(conCategoria).hasSize(1);
    }

    @Test
    @DisplayName("no strict match -> relaxed rows (>= 50% of terms) flagged parcial with terminosFaltantes")
    void relaxedRowsAreFlagged() throws Exception {
        SearchProductsTool tool = toolCon(List.of(
                disco("https://a.com/1", "SSD Kingston 1TB", "Kingston", 100, null),
                disco("https://a.com/2", "Mouse Kingston", "Kingston", 100, null),
                disco("https://a.com/3", "Teclado Gamer", "Otra", 100, null)));

        JsonNode matches = search(tool, MAPPER.createObjectNode().put("query", "ssd kingston 2tb"));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).get("url").asText()).isEqualTo("https://a.com/1");
        assertThat(matches.get(0).get("coincidencia").asText()).isEqualTo("parcial");
        assertThat(matches.get(0).get("terminosFaltantes")).extracting(JsonNode::asText).containsExactly("2tb");
    }

    @Test
    @DisplayName("strict matches win: relaxed rows never show up next to a full match")
    void strictSuppressesRelaxed() throws Exception {
        SearchProductsTool tool = toolCon(List.of(
                disco("https://a.com/1", "SSD Kingston 1TB", "Kingston", 100, null),
                disco("https://a.com/2", "SSD Crucial 1TB", "Crucial", 100, null)));

        JsonNode matches = search(tool, MAPPER.createObjectNode().put("query", "ssd kingston"));

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).has("coincidencia")).isFalse();
    }

    @Test
    @DisplayName("results are ordered by relevance (brand hit first) and carry relevancia with 2 decimals")
    void relevanceOrder() throws Exception {
        SearchProductsTool tool = toolCon(List.of(
                disco("https://a.com/nombre", "Pendrive Kingston 32GB", "Generica", 100, null),
                disco("https://a.com/marca", "Pendrive 32GB", "Kingston", 100, null),
                disco("https://a.com/otro", "Mouse Gamer", "Otra", 100, null)));

        JsonNode matches = search(tool, MAPPER.createObjectNode().put("query", "kingston"));

        assertThat(matches.findValuesAsText("url")).containsExactly("https://a.com/marca", "https://a.com/nombre");
        double primero = matches.get(0).get("relevancia").asDouble();
        assertThat(primero).isGreaterThan(matches.get(1).get("relevancia").asDouble());
        assertThat(primero).isEqualTo(Math.round(primero * 100) / 100.0);
    }

    @Test
    @DisplayName("perf sanity: 16k-product snapshot, index build then repeated queries (measured, not asserted)")
    void perfSanityOn16kProducts() throws Exception {
        String[] tipos = {"Remera", "Buzo", "Zapatilla", "Pantalon", "Campera", "Short", "SSD", "Mouse", "Teclado", "Memoria RAM"};
        String[] marcas = {"Nike", "Adidas", "Kingston", "Puma", "Topper", "Hiksemi", "Logitech", "Crucial"};
        String[] rasgos = {"Negro", "Blanco", "Azul", "1TB", "500GB", "Running", "Oversize", "Gamer", "Urbano", "Pro"};
        java.util.ArrayList<Product> big = new java.util.ArrayList<>();
        for (int i = 0; i < 16_000; i++) {
            big.add(disco("https://a.com/" + i,
                    tipos[i % 10] + " " + rasgos[(i / 10) % 10] + " " + marcas[(i / 7) % 8] + " modelo" + (i % 997),
                    marcas[(i / 7) % 8], 100 + i, i % 3 == 0 ? 200.0 + i : null));
        }
        SearchProductsTool tool = toolCon(big);
        JsonNode args = MAPPER.createObjectNode().put("query", "tenés zapatillas kingstom 1 tb");

        long t0 = System.nanoTime();
        JsonNode first = search(tool, args);
        long primera = System.nanoTime() - t0;
        long total = 0;
        for (int i = 0; i < 20; i++) {
            long t = System.nanoTime();
            search(tool, args);
            total += System.nanoTime() - t;
        }
        System.out.printf("[perf] 16k snapshot: first query (index build) %d ms, warm query avg %.1f ms%n",
                primera / 1_000_000, total / 20 / 1e6);
        assertThat(first.isArray()).isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private SearchProductsTool toolCon(List<Product> catalogo) {
        ScraperService service = mock(ScraperService.class);
        when(service.getLastResult()).thenReturn(mockResult(catalogo));
        return new SearchProductsTool(service);
    }

    private JsonNode search(SearchProductsTool tool, JsonNode args) throws Exception {
        ToolResult result = tool.execute(args);
        assertThat(result.isError()).as(result.content()).isFalse();
        return MAPPER.readTree(result.content());
    }

    private Product disco(String url, String nombre, String marca, double precio, Double precioOrig) {
        return new Product("Sitio", nombre, precio, precioOrig, url, "img",
                "Almacenamiento", "unisex", List.of(), Product.MlScore.EMPTY, marca,
                "tecnologia", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, "Sub");
    }

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
