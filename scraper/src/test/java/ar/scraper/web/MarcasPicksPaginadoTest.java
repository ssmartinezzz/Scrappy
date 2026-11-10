package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.api.ApiException;
import ar.scraper.model.Product;
import ar.scraper.model.Product.MlScore;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.dto.MarcasPicksDtos;
import ar.scraper.web.support.Wire;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** GET /api/mejores/picks: every product of a category, keyset-paged, never skip-N. */
@DisplayName("MarcasPicksController — /api/mejores/picks keyset pagination")
class MarcasPicksPaginadoTest {

    private ScraperService service;
    private MarcasPicksController controller;

    @BeforeEach
    void setUp() {
        service = mock(ScraperService.class);
        controller = new MarcasPicksController(service, new CatalogoDerivadoCache(service, mock(GroupingService.class)));
    }

    private static Product p(String url, int scoreP, String categoria, String genero) {
        return Product.builder()
                .sitio("Sitio").nombre("Producto " + url).precio(1000).url(url).imagenUrl("img")
                .categoria(categoria).genero(genero).talles(List.of())
                .ml(new MlScore(scoreP, "", false, "estable", scoreP))
                .marca("Marca").rubro("indumentaria").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).subCategoria("").build();
    }

    private AggregatedResult catalogo(List<Product> ps) {
        return new AggregatedResult(ps, Map.of("Sitio", ps.size()), Map.of(), ResultAggregator.calcularFacets(ps), 0, 0);
    }

    /** 57 remeras with many score ties (and unscored ones) plus 3 shorts that must never leak in. */
    private List<Product> remeras() {
        List<Product> ps = new ArrayList<>();
        for (int i = 0; i < 57; i++) ps.add(p("https://s/r" + String.format("%03d", i), (i * 7) % 5 == 0 ? 0 : 10 + (i % 4), "Remera", "unisex"));
        for (int i = 0; i < 3; i++) ps.add(p("https://s/s" + i, 5, "Short", "unisex"));
        return ps;
    }

    private JsonNode pagina(String categoria, String cursor, Integer limit) {
        ResponseEntity<?> resp = controller.picksDeCategoria(categoria, null, null, null, cursor, limit);
        return Wire.data(resp);
    }

    @Test
    @DisplayName("walking the cursors yields every product once, in (score, url) order, ending with a null cursor")
    void recorreTodoSinRepetirNiSaltear() {
        var ps = remeras();
        when(service.getLastResult()).thenReturn(catalogo(ps));

        List<String> vistos = new ArrayList<>();
        String cursor = null;
        int paginas = 0;
        do {
            JsonNode d = pagina("Remera", cursor, 10);
            d.path("picks").forEach(n -> vistos.add(n.path("url").asText()));
            cursor = d.path("nextCursor").isNull() || d.path("nextCursor").isMissingNode() ? null : d.path("nextCursor").asText();
            paginas++;
        } while (cursor != null);

        List<String> esperado = ps.stream().filter(x -> "Remera".equals(x.categoria()))
                .sorted(Comparator.comparingInt((Product x) -> x.ml().scoreP() > 0 ? x.ml().scoreP() : 999)
                        .thenComparing(Product::url))
                .map(Product::url).toList();
        assertThat(vistos).containsExactlyElementsOf(esperado);
        assertThat(paginas).isEqualTo(6);
    }

    @Test
    @DisplayName("the default page is 24 and the limit is clamped to 100")
    void limitePorDefectoYTope() {
        var ps = new ArrayList<Product>();
        for (int i = 0; i < 150; i++) ps.add(p("https://s/r" + String.format("%03d", i), 20, "Remera", "unisex"));
        when(service.getLastResult()).thenReturn(catalogo(ps));

        assertThat(pagina("Remera", null, null).path("picks")).hasSize(24);
        assertThat(pagina("Remera", null, 1000).path("picks")).hasSize(100);
        assertThat(pagina("Remera", null, 0).path("picks")).hasSize(1);
    }

    @Test
    @DisplayName("an unknown category is an empty last page, not an error")
    void categoriaDesconocida() {
        when(service.getLastResult()).thenReturn(catalogo(remeras()));

        JsonNode d = pagina("Nada", null, 10);

        assertThat(d.path("picks")).isEmpty();
        assertThat(d.path("nextCursor").isNull() || d.path("nextCursor").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("a malformed cursor is a 400")
    void cursorMalformado() {
        when(service.getLastResult()).thenReturn(catalogo(remeras()));
        for (String malo : new String[]{"###", "bm8tcGlwZQ" /* "no-pipe" */,
                Base64.getUrlEncoder().withoutPadding().encodeToString("abc|https://s/r1".getBytes(StandardCharsets.UTF_8))}) {
            assertThatThrownBy(() -> pagina("Remera", malo, 10)).as(malo)
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    @DisplayName("the genero filter applies: mujer excludes hombre-only rows")
    void filtraPorGenero() {
        var ps = List.of(p("https://s/h", 10, "Remera", "hombre"), p("https://s/m", 11, "Remera", "mujer"),
                p("https://s/u", 12, "Remera", "unisex"));
        when(service.getLastResult()).thenReturn(catalogo(ps));

        ResponseEntity<?> resp = controller.picksDeCategoria("Remera", null, null, "mujer", null, 10);

        Set<String> urls = new HashSet<>();
        Wire.data(resp).path("picks").forEach(n -> urls.add(n.path("url").asText()));
        assertThat(urls).containsExactlyInAnyOrder("https://s/m", "https://s/u");
    }
}
