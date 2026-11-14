package ar.scraper.agent;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.Facets;
import ar.scraper.catalog.PoliticaStock;
import ar.scraper.model.Product;
import ar.scraper.web.ScraperService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("search_products — stock por fila")
class SearchProductsToolStockTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant AHORA = Instant.parse("2026-11-13T12:00:00Z");

    private static Product ssd(String url, Boolean enStock, Instant chequeado) {
        return Product.builder().sitio("Sitio").nombre("SSD Kingston " + url).precio(100).url(url).imagenUrl("img")
                .categoria("Almacenamiento").genero("unisex").talles(List.of()).marca("Kingston").rubro("tecnologia")
                .subCategoria("Sub").enStock(enStock).stockChequeadoAt(chequeado).vistoEnStockAt(chequeado).build();
    }

    private JsonNode buscar(Product... productos) throws Exception {
        ScraperService service = mock(ScraperService.class);
        when(service.getLastResult()).thenReturn(new AggregatedResult(List.of(productos), Map.of(), Map.of(),
                new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of()), 0, 0));
        var tool = new SearchProductsTool(service,
                new PoliticaStock(Clock.fixed(AHORA, ZoneOffset.UTC), Duration.ofDays(7)));
        ToolResult r = tool.execute(MAPPER.createObjectNode().put("query", "ssd"));
        assertThat(r.isError()).isFalse();
        return MAPPER.readTree(r.content());
    }

    @Test
    @DisplayName("every row says en_stock / sin_stock / desconocido, stale reads unknown")
    void rowsCarryStock() throws Exception {
        Instant fresco = AHORA.minus(Duration.ofHours(1));
        JsonNode rows = buscar(ssd("a", true, fresco), ssd("b", false, fresco), ssd("c", null, null),
                ssd("d", true, AHORA.minus(Duration.ofDays(20))));

        Map<String, String> porUrl = new java.util.HashMap<>();
        rows.forEach(r -> porUrl.put(r.path("url").asText(), r.path("stock").asText()));
        assertThat(porUrl).containsEntry("a", "en_stock").containsEntry("b", "sin_stock")
                .containsEntry("c", "desconocido").containsEntry("d", "desconocido");
    }

    @Test
    @DisplayName("the rendered answer flags a sold-out row and stays silent on the rest")
    void rendererFlagsSoldOut() {
        String out = SearchAnswerRenderer.render("""
                [{"url":"https://a.com/1","nombre":"SSD A","sitio":"S","precio":100,"stock":"sin_stock"},
                 {"url":"https://a.com/2","nombre":"SSD B","sitio":"S","precio":200,"stock":"en_stock"},
                 {"url":"https://a.com/3","nombre":"SSD C","sitio":"S","precio":300,"stock":"desconocido"}]""");

        assertThat(out.lines().toList()).containsExactly(
                "Encontré 3 productos:",
                "- [SSD A](https://a.com/1) — S — $100 — **sin stock**",
                "- [SSD B](https://a.com/2) — S — $200",
                "- [SSD C](https://a.com/3) — S — $300");
    }
}
