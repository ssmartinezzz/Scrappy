package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * scrape-run-persistence-and-resume, slice 4 — the bound end to end (task 4.7).
 *
 * <p>{@code CatalogQueryRepositoryBoundTest} proves each SQL surface honours a
 * bound it is handed. This proves the endpoints actually hand it one, and hand
 * the SAME one to the 204 gate, the page, the counters and the facets. A bound
 * that reaches {@code buscar} and not {@code resumen} makes the pills advertise
 * filters the page cannot satisfy, and every unit test still passes.</p>
 */
@Epic("REST API")
@Feature("Scrape run persistence (scrape-run-persistence-and-resume)")
@Story("Readers are isolated from a run in flight")
@DisplayName("ApiController — la cota de lectura llega a /api/data y /api/facets")
class ApiControllerCotaDeLecturaTest extends ar.scraper.db.support.PostgresTestBase {

    private static final String VIEJO  = "https://uno.com/viejo";
    private static final String FRESCO = "https://dos.com/fresco";

    private ScraperService service;
    private DatabaseService db;
    private ApiController controller;
    private Instant runStart;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(ScraperService.class);
        ScraperConfig config = mock(ScraperConfig.class);
        when(config.getMoneda()).thenReturn("ARS");
        db = new DatabaseService(dataSource());
        controller = new ApiController(service, mock(InflacionService.class), config,
                mock(ResultAggregator.class), db, mock(GroupingService.class),
                mock(PythonRunner.class), mock(OutfitService.class),
                mock(RecommendationService.class));

        runStart = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        db.upsertProductos(List.of(
                producto("Uno", VIEJO,  1000.0, "Nike"),
                producto("Dos", FRESCO, 2000.0, "Adidas")));
        fijarTouchedAt(VIEJO,  runStart.minusSeconds(10));
        fijarTouchedAt(FRESCO, runStart.plusSeconds(5));
    }

    private void hayCorridaEnCurso() {
        when(service.cotaDeLectura()).thenReturn(Optional.of(runStart));
    }

    @Test
    @DisplayName("/api/data sirve sólo lo previo a la corrida, y sus contadores coinciden")
    void dataRespetaLaCota() {
        hayCorridaEnCurso();

        ObjectNode body = data().getBody();
        JsonNode meta = body.path("meta");

        assertThat(body.path("productos")).hasSize(1);
        assertThat(body.path("productos").get(0).path("url").asText()).isEqualTo(VIEJO);
        assertThat(meta.path("total").asInt())
                .as("el total del pager sale del mismo predicado que la página")
                .isEqualTo(1);
        assertThat(meta.path("rangMax").asDouble())
                .as("el rango de precios sale de resumen(): si va sin acotar, el "
                    + "slider ofrece un máximo que ningún producto de la página tiene")
                .isEqualTo(1000.0);
    }

    @Test
    @DisplayName("las facetas de /api/data no ofrecen filtros que la página no puede cumplir")
    void facetasDeDataRespetanLaCota() {
        hayCorridaEnCurso();

        JsonNode facets = data().getBody().path("meta").path("facets");

        assertThat(facets.path("marcas").has("Nike")).isTrue();
        assertThat(facets.path("marcas").has("Adidas")).isFalse();
    }

    @Test
    @DisplayName("/api/facets suelto usa la misma cota que /api/data")
    void facetsSueltoRespetaLaCota() {
        hayCorridaEnCurso();

        ObjectNode body = controller.facets().getBody();

        assertThat(body.path("marcas").has("Nike")).isTrue();
        assertThat(body.path("marcas").has("Adidas"))
                .as("es el par de llamadas que ya estaba latente: resumen y facetas "
                    + "juntas, en el endpoint que nadie mira")
                .isFalse();
    }

    @Test
    @DisplayName("sin corrida abierta se sirve el catálogo entero")
    void sinCotaSeSirveTodo() {
        when(service.cotaDeLectura()).thenReturn(Optional.empty());

        JsonNode meta = data().getBody().path("meta");

        assertThat(meta.path("total").asInt()).isEqualTo(2);
        assertThat(meta.path("rangMax").asDouble()).isEqualTo(2000.0);
    }

    @Test
    @DisplayName("el detalle de producto queda EXENTO: no puede 404 a mitad de una corrida")
    void detalleDeProductoNoSeAcota() throws Exception {
        hayCorridaEnCurso();

        // Estructural, no una excepción escrita a mano: productoDetalle entra por
        // obtenerProductoPorKey, que nunca pasa por CatalogQueryRepository. Este
        // test existe para que agregarle la cota ahí rompa el build.
        String key = productoKeyDe(FRESCO);
        ResponseEntity<Object> resp = controller.productoDetalle(key);

        assertThat(resp.getStatusCode().value())
                .as("el producto lo está re-tocando la corrida; el detalle igual responde")
                .isEqualTo(200);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<ObjectNode> data() {
        return controller.data(1, 24, null, null, null, null, null, null, null, null,
                null, null, "precio_asc", null, null, null, null, null, null, null, null);
    }

    private Product producto(String sitio, String url, double precio, String marca) {
        return new Product(sitio, "Producto " + url, precio, null, url, "img",
                "Remera", "unisex", List.of("M"), Product.MlScore.EMPTY, marca,
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1, "", Product.VisualAttrs.EMPTY);
    }

    private void fijarTouchedAt(String url, Instant cuando) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE productos SET touched_at = ? WHERE url = ?")) {
            ps.setObject(1, OffsetDateTime.ofInstant(cuando, ZoneOffset.UTC));
            ps.setString(2, url);
            ps.executeUpdate();
        }
    }

    private String productoKeyDe(String url) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT producto_key FROM productos WHERE url = ?")) {
            ps.setString(1, url);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }
}
