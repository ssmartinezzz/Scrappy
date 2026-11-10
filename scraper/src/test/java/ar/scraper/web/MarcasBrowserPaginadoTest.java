package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.api.ApiException;
import ar.scraper.model.Product;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.support.Wire;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** GET /api/marcas-browser: every brand with >= 2 products, keyset-paged, never skip-N. */
@DisplayName("MarcasPicksController — /api/marcas-browser keyset pagination")
class MarcasBrowserPaginadoTest {

    private ScraperService service;
    private MarcasPicksController controller;

    @BeforeEach
    void setUp() {
        service = mock(ScraperService.class);
        when(service.snapshotVersion()).thenReturn(1L);
        controller = new MarcasPicksController(service, new CatalogoDerivadoCache(service, mock(GroupingService.class)));
    }

    private static void add(List<Product> out, String marca, double precio, int n) {
        for (int i = 0; i < n; i++) {
            out.add(Product.builder().sitio("S").nombre(marca + i).precio(precio)
                    .url("https://t/" + marca + "/" + i).marca(marca).categoria("Remera").build());
        }
    }

    private void catalogo(List<Product> ps) {
        when(service.getLastResult()).thenReturn(
                new AggregatedResult(ps, Map.of("S", ps.size()), Map.of(), ResultAggregator.calcularFacets(ps), 0, 0));
    }

    private JsonNode pagina(String sort, String cursor, Integer limit) {
        return Wire.data(controller.marcasBrowser(null, null, sort, cursor, limit));
    }

    private static String cursorDe(JsonNode d) {
        return d.path("nextCursor").isNull() || d.path("nextCursor").isMissingNode() ? null : d.path("nextCursor").asText();
    }

    private List<String> recorrer(String sort, int limit) {
        List<String> vistos = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode d = pagina(sort, cursor, limit);
            d.path("marcas").forEach(n -> vistos.add(n.path("marca").asText()));
            cursor = cursorDe(d);
        } while (cursor != null);
        return vistos;
    }

    private void assertBadRequest(String sort, String cursor) {
        assertThatThrownBy(() -> pagina(sort, cursor, 10))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("nombre is alphabetical, accent- and case-insensitive")
    void nombreEsAlfabeticoSinAcentosNiMayusculas() {
        List<Product> ps = new ArrayList<>();
        for (String m : new String[]{"Zara", "arena", "Éxito", "Beta", "exa"}) add(ps, m, 100, 2);
        catalogo(ps);

        assertThat(recorrer("nombre", 2)).containsExactly("arena", "Beta", "exa", "Éxito", "Zara");
    }

    @Test
    @DisplayName("an omitted or blank sort is nombre")
    void ordenPorDefectoEsNombre() {
        List<Product> ps = new ArrayList<>();
        add(ps, "Zeta", 100, 5);
        add(ps, "Alfa", 100, 2);
        catalogo(ps);

        assertThat(recorrer("", 10)).containsExactly("Alfa", "Zeta");
        assertThat(recorrer(null, 10)).containsExactly("Alfa", "Zeta");
    }

    @Test
    @DisplayName("no brand with >= 2 products is cut off: 250 brands are all reachable, once each")
    void sinTopeDeCien() {
        List<Product> ps = new ArrayList<>();
        for (int i = 0; i < 250; i++) add(ps, String.format("M%03d", i), 100 + i, 2);
        add(ps, "Solo", 100, 1);
        catalogo(ps);

        List<String> vistos = recorrer("nombre", 48);

        assertThat(vistos).hasSize(250).doesNotHaveDuplicates().doesNotContain("Solo");
        assertThat(pagina("nombre", null, 48).path("total").asInt()).isEqualTo(250);
    }

    @Test
    @DisplayName("every order is total: ties break by brand name, so walking pages never repeats or skips")
    void ordenesTotalesConEmpates() {
        List<Product> ps = new ArrayList<>();
        for (String m : new String[]{"Delta", "alfa", "Charlie", "Bravo", "Echo"}) add(ps, m, 100, 2);
        catalogo(ps);

        assertThat(recorrer("count", 2)).containsExactly("alfa", "Bravo", "Charlie", "Delta", "Echo");
        assertThat(recorrer("precio_asc", 1)).containsExactly("alfa", "Bravo", "Charlie", "Delta", "Echo");
        assertThat(recorrer("precio_desc", 3)).containsExactly("alfa", "Bravo", "Charlie", "Delta", "Echo");
    }

    @Test
    @DisplayName("count is descending and precio sorts by average price")
    void ordenesPorCantidadYPrecio() {
        List<Product> ps = new ArrayList<>();
        add(ps, "Cheap", 100, 2);
        add(ps, "Mid", 200, 4);
        add(ps, "Dear", 300, 3);
        catalogo(ps);

        assertThat(recorrer("count", 1)).containsExactly("Mid", "Dear", "Cheap");
        assertThat(recorrer("precio_asc", 2)).containsExactly("Cheap", "Mid", "Dear");
        assertThat(recorrer("precio_desc", 2)).containsExactly("Dear", "Mid", "Cheap");
    }

    @Test
    @DisplayName("nextCursor is null on the last page; limit defaults to 48 and is clamped to 1..100")
    void limiteYUltimaPagina() {
        List<Product> ps = new ArrayList<>();
        for (int i = 0; i < 150; i++) add(ps, String.format("M%03d", i), 100, 2);
        catalogo(ps);

        assertThat(pagina("nombre", null, null).path("marcas")).hasSize(48);
        assertThat(pagina("nombre", null, 1000).path("marcas")).hasSize(100);
        assertThat(pagina("nombre", null, 0).path("marcas")).hasSize(1);
        JsonNode ultima = pagina("nombre", cursorDe(pagina("nombre", null, 100)), 100);
        assertThat(ultima.path("marcas")).hasSize(50);
        assertThat(cursorDe(ultima)).isNull();
    }

    @Test
    @DisplayName("the cursor resumes strictly after its key even when that brand is gone")
    void reanudaDespuesDeLaClaveAunSinLaMarca() {
        List<Product> ps = new ArrayList<>();
        for (String m : new String[]{"A", "B", "C", "D"}) add(ps, m, 100, 2);
        catalogo(ps);
        String cursor = cursorDe(pagina("nombre", null, 2));

        List<Product> sinB = new ArrayList<>();
        for (String m : new String[]{"A", "C", "D"}) add(sinB, m, 100, 2);
        catalogo(sinB);

        List<String> resto = new ArrayList<>();
        pagina("nombre", cursor, 10).path("marcas").forEach(n -> resto.add(n.path("marca").asText()));
        assertThat(resto).containsExactly("C", "D");
    }

    @Test
    @DisplayName("a malformed cursor, a cursor of another sort and an unknown sort are 400")
    void cursoresYOrdenesInvalidos() {
        List<Product> ps = new ArrayList<>();
        for (int i = 0; i < 5; i++) add(ps, "M" + i, 100 + i, 2);
        catalogo(ps);
        String deNombre = cursorDe(pagina("nombre", null, 2));

        assertBadRequest("nombre", "###");
        assertBadRequest("nombre", Base64.getUrlEncoder().withoutPadding()
                .encodeToString("sin-separadores".getBytes(StandardCharsets.UTF_8)));
        assertBadRequest("count", deNombre);
        assertBadRequest("precio_asc", deNombre);
        assertBadRequest("raro", null);
    }
}
