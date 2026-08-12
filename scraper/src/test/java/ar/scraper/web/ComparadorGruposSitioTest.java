package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.grouping.JaccardSimilarity;
import ar.scraper.aggregator.grouping.ProductIdentity;
import ar.scraper.db.DatabaseService;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code sitio} filter on {@code GET /api/grupos}.
 *
 * <p>The parameter was declared in {@code ApiController}, threaded all the way
 * down to {@code ComparadorEndpoints.grupos}, and then never used — a request
 * with {@code ?sitio=freres} was silently answered as if no filter had been
 * sent at all.</p>
 *
 * <p>It is a <b>post-filter</b>: the whole catalog is grouped first, then only
 * groups that <em>contain</em> that site are kept. The alternative — filtering
 * products before grouping, the way {@code q}/{@code categoria}/{@code rubro}
 * work — cannot be right here, because this endpoint exists to compare the
 * same article ACROSS sites and defaults to {@code minSitios=2}: narrowing the
 * input to a single site would make every group single-site and the endpoint
 * would return an empty list every time.</p>
 *
 * <p>Interaction with pagination matters as much as the filter itself: the
 * filter runs BEFORE the page window is cut, so {@code total} reports the
 * filtered count, not the unfiltered one.</p>
 */
@Epic("REST API")
@Feature("Comparador multi-sitio")
@Story("Filtro por sitio")
@DisplayName("ComparadorEndpoints — /api/grupos?sitio=")
class ComparadorGruposSitioTest {

    private ScraperService service;
    private ComparadorEndpoints endpoints;

    @BeforeEach
    void setUp() {
        service = mock(ScraperService.class);
        endpoints = new ComparadorEndpoints(service, mock(DatabaseService.class),
                new GroupingService(new ProductIdentity(), new JaccardSimilarity()));
    }

    private static Product producto(String sitio, String nombre, double precio) {
        return new Product(sitio, nombre, precio, null,
                "https://" + sitio + "/" + nombre.hashCode() + precio, "img",
                "Zapatilla", "unisex", List.of("M"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1, "", Product.VisualAttrs.EMPTY);
    }

    private void publicar(Product... productos) {
        List<Product> lista = new ArrayList<>(List.of(productos));
        lista.sort(Comparator.comparingDouble(Product::precio));
        when(service.getLastResult()).thenReturn(new AggregatedResult(List.copyOf(lista),
                Map.of(), Map.of(), ResultAggregator.calcularFacets(lista),
                lista.get(0).precio(), lista.get(lista.size() - 1).precio()));
    }

    private JsonNode consultar(String sitio, int minSitios) {
        ResponseEntity<Object> resp = endpoints.grupos(null, sitio, null, null, minSitios, 0, 20);
        return (JsonNode) resp.getBody();
    }

    /**
     * Three distinct articles, each listed by a different pair of sites.
     *
     * <p>Within an article the ONLY difference is the colour. That is on
     * purpose: colours are stop words, so both listings tokenize to the same
     * identity key and land in the same group. Adding any other distinct word
     * (a "Urbana", say) would push them into separate pre-groups and each
     * article would silently become two single-site groups — which
     * {@code minSitios=2} then drops, leaving a fixture that tests nothing.</p>
     */
    private void publicarCatalogoDeTresArticulos() {
        publicar(
                // Articulo A: freres + midway
                producto("freres", "Nike Air Force Blanco", 50000),
                producto("midway", "Nike Air Force Negro", 55000),
                // Articulo B: midway + vcp
                producto("midway", "Nike Superstar Retro Azul", 60000),
                producto("vcp",    "Nike Superstar Retro Verde", 62000),
                // Articulo C: freres + vcp
                producto("freres", "Nike Gazelle Clasica Roja", 70000),
                producto("vcp",    "Nike Gazelle Clasica Gris", 72000));
    }

    private static List<String> nombresDe(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.path("grupos").forEach(g -> out.add(g.path("nombre").asText()));
        return out;
    }

    // ─── The filter ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("deja solo los grupos que incluyen ese sitio")
    void dejaSoloLosGruposQueIncluyenEseSitio() {
        publicarCatalogoDeTresArticulos();

        JsonNode body = consultar("freres", 2);

        // Articulos A y C tienen freres; el B (midway+vcp) no.
        assertThat(nombresDe(body))
                .allSatisfy(n -> assertThat(n).doesNotContain("Superstar"))
                .hasSize(2);
        assertThat(body.path("total").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("otro sitio devuelve otro subconjunto — no es que filtre siempre igual")
    void otroSitioDevuelveOtroSubconjunto() {
        publicarCatalogoDeTresArticulos();

        assertThat(nombresDe(consultar("vcp", 2)))
                .allSatisfy(n -> assertThat(n).doesNotContain("Air Force"))
                .hasSize(2);
        assertThat(nombresDe(consultar("midway", 2)))
                .allSatisfy(n -> assertThat(n).doesNotContain("Gazelle"))
                .hasSize(2);
    }

    @Test
    @DisplayName("es case-insensitive, igual que los filtros de categoria y rubro")
    void esCaseInsensitive() {
        publicarCatalogoDeTresArticulos();

        assertThat(consultar("FRERES", 2).path("total").asInt())
                .isEqualTo(consultar("freres", 2).path("total").asInt())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("un sitio inexistente devuelve cero grupos, no el catálogo entero")
    void sitioInexistenteDevuelveCero() {
        publicarCatalogoDeTresArticulos();

        JsonNode body = consultar("no-existe", 2);
        assertThat(body.path("total").asInt()).isZero();
        assertThat(body.path("grupos")).isEmpty();
    }

    @Test
    @DisplayName("sin sitio (null o en blanco) no filtra nada")
    void sinSitioNoFiltra() {
        publicarCatalogoDeTresArticulos();

        assertThat(consultar(null, 2).path("total").asInt()).isEqualTo(3);
        assertThat(consultar("", 2).path("total").asInt()).isEqualTo(3);
        assertThat(consultar("   ", 2).path("total").asInt()).isEqualTo(3);
    }

    // ─── Interaction with the rest of the endpoint ───────────────────────────

    @Test
    @DisplayName("se combina con minSitios en vez de anularlo")
    void seCombinaConMinSitios() {
        publicar(
                producto("freres", "Nike Air Force Blanco", 50000),
                producto("midway", "Nike Air Force Negro", 55000),
                // Solo freres: sobrevive con minSitios=1, no con minSitios=2
                producto("freres", "Nike Pegasus Running", 80000));

        assertThat(consultar("freres", 2).path("total").asInt())
                .as("con minSitios=2 solo pasa el articulo que esta en dos sitios")
                .isEqualTo(1);
        assertThat(consultar("freres", 1).path("total").asInt())
                .as("con minSitios=1 pasan los dos articulos de freres")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("el total refleja el filtro: se aplica ANTES de recortar la página")
    void elTotalReflejaElFiltroNoElCatalogoCompleto() {
        publicarCatalogoDeTresArticulos();

        ResponseEntity<Object> resp = endpoints.grupos(null, "freres", null, null, 2, 0, 1);
        JsonNode body = (JsonNode) resp.getBody();

        assertThat(body.path("total").asInt()).as("total filtrado, no 3").isEqualTo(2);
        assertThat(body.path("grupos")).hasSize(1);   // recortado por size=1
    }

    @Test
    @DisplayName("sin catálogo en memoria sigue devolviendo 204")
    void sinCatalogoDevuelve204() {
        when(service.getLastResult()).thenReturn(null);
        assertThat(endpoints.grupos(null, "freres", null, null, 2, 0, 20).getStatusCode().value())
                .isEqualTo(204);
    }
}
