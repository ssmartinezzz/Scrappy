package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.model.Product;
import ar.scraper.model.Product.MlScore;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.support.Wire;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** GET /api/mejores/picks?subCategoria=: the sub filters the cached ranking BEFORE keyset paging. */
@DisplayName("MarcasPicksController — /api/mejores/picks subCategoria filter")
class MarcasPicksSubFiltroTest {

    private ScraperService service;
    private MarcasPicksController controller;

    @BeforeEach
    void setUp() {
        service = mock(ScraperService.class);
        controller = ar.scraper.web.support.MarcasPicksDePrueba.controller(service, new CatalogoDerivadoCache(service, mock(GroupingService.class)));
    }

    private static Product p(String url, int scoreP, String sub) {
        return Product.builder()
                .sitio("Sitio").nombre("Producto " + url).precio(1000).url(url).imagenUrl("img")
                .categoria("Almacenamiento").genero("unisex").talles(List.of())
                .ml(new MlScore(scoreP, "", false, "estable", scoreP))
                .marca("Marca").rubro("tecnologia").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).subCategoria(sub).build();
    }

    private AggregatedResult catalogo(List<Product> ps) {
        return new AggregatedResult(ps, Map.of("Sitio", ps.size()), Map.of(), ResultAggregator.calcularFacets(ps), 0, 0);
    }

    /** 60 ssd ranked first, 30 hdd sitting behind them, 5 without sub and 3 "inalámbrico" at the very end. */
    private List<Product> almacenamiento() {
        List<Product> ps = new ArrayList<>();
        for (int i = 0; i < 60; i++) ps.add(p("https://s/ssd" + String.format("%03d", i), 10 + i % 7, "ssd"));
        for (int i = 0; i < 30; i++) ps.add(p("https://s/hdd" + String.format("%03d", i), 90 + i % 5, "hdd"));
        for (int i = 0; i < 5; i++) ps.add(p("https://s/x" + i, 50, ""));
        for (int i = 0; i < 3; i++) ps.add(p("https://s/w" + i, 99, "inalámbrico"));
        return ps;
    }

    private JsonNode pagina(String sub, String cursor, int limit) {
        return Wire.data(controller.picksDeCategoria("Almacenamiento", "tecnologia", null, null, sub, cursor, limit));
    }

    private static String cursorDe(JsonNode d) {
        JsonNode c = d.path("nextCursor");
        return c.isNull() || c.isMissingNode() ? null : c.asText();
    }

    @Test
    @DisplayName("only the requested sub comes back, total is the filtered count, and the cursor walk has no gaps or repeats")
    void filtraAntesDePaginar() {
        when(service.getLastResult()).thenReturn(catalogo(almacenamiento()));

        List<String> vistos = new ArrayList<>();
        String cursor = null;
        int paginas = 0;
        do {
            JsonNode d = pagina("hdd", cursor, 12);
            assertThat(d.path("total").asInt()).isEqualTo(30);
            d.path("picks").forEach(n -> {
                assertThat(n.path("subCategoria").asText()).isEqualTo("hdd");
                vistos.add(n.path("url").asText());
            });
            cursor = cursorDe(d);
            paginas++;
        } while (cursor != null);

        assertThat(paginas).isEqualTo(3);
        assertThat(vistos).hasSize(30).doesNotHaveDuplicates().allMatch(u -> u.startsWith("https://s/hdd"));
    }

    @Test
    @DisplayName("the filtered pages are the same sequence the unfiltered ranking shows for that sub")
    void mismoOrdenQueElRankingCompleto() {
        when(service.getLastResult()).thenReturn(catalogo(almacenamiento()));

        List<String> todo = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode d = pagina(null, cursor, 100);
            d.path("picks").forEach(n -> { if ("hdd".equals(n.path("subCategoria").asText())) todo.add(n.path("url").asText()); });
            cursor = cursorDe(d);
        } while (cursor != null);

        List<String> filtrado = new ArrayList<>();
        cursor = null;
        do {
            JsonNode d = pagina("hdd", cursor, 7);
            d.path("picks").forEach(n -> filtrado.add(n.path("url").asText()));
            cursor = cursorDe(d);
        } while (cursor != null);

        assertThat(filtrado).containsExactlyElementsOf(todo);
    }

    @Test
    @DisplayName("blank or absent sub is the unfiltered list; the param is trimmed; accents are kept")
    void sinSubNoCambiaNada() {
        when(service.getLastResult()).thenReturn(catalogo(almacenamiento()));

        assertThat(pagina(null, null, 10).path("total").asInt()).isEqualTo(98);
        assertThat(pagina("", null, 10).path("total").asInt()).isEqualTo(98);
        assertThat(pagina("   ", null, 10).path("total").asInt()).isEqualTo(98);
        assertThat(pagina(" hdd ", null, 10).path("total").asInt()).isEqualTo(30);
        assertThat(pagina("inalámbrico", null, 10).path("total").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("an unknown sub is an empty page with total 0, not an error")
    void subDesconocida() {
        when(service.getLastResult()).thenReturn(catalogo(almacenamiento()));

        JsonNode d = pagina("zip", null, 10);

        assertThat(d.path("picks")).isEmpty();
        assertThat(d.path("total").asInt()).isZero();
        assertThat(cursorDe(d)).isNull();
    }
}
