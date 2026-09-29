package ar.scraper.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SearchAnswerRenderer")
class SearchAnswerRendererTest {

    @Test
    @DisplayName("strict rows: count line, then one markdown link line per row with es-AR price, no decimals")
    void strictRows() {
        String json = """
                [{"url":"https://a.com/1","nombre":"HD SSD 1TB Wave","sitio":"Compragamer","precio":264400.0},
                 {"url":"https://a.com/2","nombre":"SSD 2TB Kingston","sitio":"Mexx","precio":1234567.6}]""";

        String out = SearchAnswerRenderer.render(json);

        assertThat(out.lines().toList()).containsExactly(
                "Encontré 2 productos:",
                "- [HD SSD 1TB Wave](https://a.com/1) — Compragamer — $264.400",
                "- [SSD 2TB Kingston](https://a.com/2) — Mexx — $1.234.568");
    }

    @Test
    @DisplayName("a single row says 'producto' in the singular")
    void singular() {
        String out = SearchAnswerRenderer.render(
                "[{\"url\":\"https://a.com/1\",\"nombre\":\"X\",\"sitio\":\"S\",\"precio\":999}]");
        assertThat(out.lines().findFirst().orElseThrow()).isEqualTo("Encontré 1 producto:");
    }

    @Test
    @DisplayName("a discounted row carries **−x%** and the original price")
    void discount() {
        String out = SearchAnswerRenderer.render("""
                [{"url":"https://a.com/1","nombre":"SSD","sitio":"S","precio":90000,
                  "precioOrig":100000.0,"descuentoPct":10}]""");
        assertThat(out).contains("- [SSD](https://a.com/1) — S — $90.000 — **−10%** (antes $100.000)");
    }

    @Test
    @DisplayName("all rows partial: 'No encontré exactamente eso' header with the union of missing terms")
    void partialHeader() {
        String out = SearchAnswerRenderer.render("""
                [{"url":"u1","nombre":"A","sitio":"S","precio":1000,"coincidencia":"parcial","terminosFaltantes":["5090","rtx"]},
                 {"url":"u2","nombre":"B","sitio":"S","precio":2000,"coincidencia":"parcial","terminosFaltantes":["5090"]}]""");
        assertThat(out.lines().findFirst().orElseThrow())
                .isEqualTo("No encontré exactamente eso. Lo más parecido (faltan: 5090, rtx):");
        assertThat(out.lines().count()).isEqualTo(3);
    }

    @Test
    @DisplayName("brackets in the name and parentheses/spaces in the url cannot break the markdown link")
    void linkIsSanitised() {
        String out = SearchAnswerRenderer.render("""
                [{"url":"https://a.com/p (1)/x y","nombre":"Mouse [Pro]","sitio":"S","precio":5}]""");
        assertThat(out).contains("- [Mouse Pro](https://a.com/p%20%281%29/x%20y) — S — $5");
    }

    @Test
    @DisplayName("empty or malformed content renders nothing (caller keeps its own path)")
    void nothingToRender() {
        assertThat(SearchAnswerRenderer.render("[]")).isNull();
        assertThat(SearchAnswerRenderer.render("not json")).isNull();
        assertThat(SearchAnswerRenderer.render(null)).isNull();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ONE_ROW = "[{\"url\":\"u\",\"nombre\":\"X\",\"sitio\":\"S\",\"precio\":1000}]";

    private static JsonNode args(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("every applied filter is stated right under the header, in a fixed order, prices es-AR")
    void filtersLine() throws Exception {
        String out = SearchAnswerRenderer.render(args("""
                {"query":"tenés algún SSD SATA de 1 TB","categoria":"Almacenamiento","genero":"hombre",
                 "precioMin":10000,"precioMax":50000.0,"enOferta":true,"excluir":["futbol","retro"],"limit":5}"""),
                ONE_ROW);

        assertThat(out.lines().toList()).containsExactly(
                "Encontré 1 producto:",
                "Filtré por: “ssd sata 1tb” · Almacenamiento · hombre · desde $10.000 · hasta $50.000"
                        + " · en oferta · sin: futbol, retro",
                "- [X](u) — S — $1.000");
    }

    @Test
    @DisplayName("only the filters present appear; the query shows its content terms, not the filler")
    void onlyPresentFilters() throws Exception {
        String out = SearchAnswerRenderer.render(args("{\"precioMax\":1234567,\"enOferta\":false}"), ONE_ROW);
        assertThat(out.lines().toList().get(1)).isEqualTo("Filtré por: hasta $1.234.567");

        String q = SearchAnswerRenderer.render(args("{\"query\":\"zapatillas de nike\"}"), ONE_ROW);
        assertThat(q.lines().toList().get(1)).isEqualTo("Filtré por: “zapatillas nike”");
    }

    @Test
    @DisplayName("with enOferta the offer words are not shown as query terms (the tool drops them too)")
    void offerWordsDroppedFromQuery() throws Exception {
        String out = SearchAnswerRenderer.render(args("{\"query\":\"ssd en descuento\",\"enOferta\":true}"), ONE_ROW);
        assertThat(out.lines().toList().get(1)).isEqualTo("Filtré por: “ssd” · en oferta");
    }

    @Test
    @DisplayName("partial header keeps its own line first; the filters line follows it")
    void filtersUnderPartialHeader() throws Exception {
        String out = SearchAnswerRenderer.render(args("{\"query\":\"rtx 5090\"}"), """
                [{"url":"u1","nombre":"A","sitio":"S","precio":1000,"coincidencia":"parcial","terminosFaltantes":["5090"]}]""");
        assertThat(out.lines().toList()).startsWith(
                "No encontré exactamente eso. Lo más parecido (faltan: 5090):",
                "Filtré por: “rtx 5090”");
    }

    @Test
    @DisplayName("no args: no filters line (the rows-only overload)")
    void noArgsNoLine() throws Exception {
        assertThat(SearchAnswerRenderer.render(null, ONE_ROW).lines().count()).isEqualTo(2);
        assertThat(SearchAnswerRenderer.render(args("{\"limit\":3}"), ONE_ROW).lines().count()).isEqualTo(2);
    }
}
