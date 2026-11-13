package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.catalog.PoliticaStock;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.db.TestDatabaseServices;
import ar.scraper.indices.IndiceService;
import ar.scraper.model.Product;
import ar.scraper.web.support.Wire;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code stock} on {@code GET /api/data}: every row says what it is (flag), and
 * {@code stock=excluir_sin_stock} drops confirmed sold-out rows only. Unknown and stale-checked rows stay (CODE-5).
 */
@DisplayName("CatalogoController — stock flag and excluir_sin_stock filter")
class CatalogoStockTest extends ar.scraper.db.support.PostgresTestBase {

    private static final String EN = "https://site.com/en";
    private static final String SIN = "https://site.com/sin";
    private static final String DESC = "https://site.com/desc";
    private static final String SIN_VIEJO = "https://site.com/sin-viejo";

    private DatabaseService db;
    private CatalogoController controller;

    @BeforeEach
    void setUp() throws SQLException {
        ScraperService service = mock(ScraperService.class);
        IndiceService indiceService = mock(IndiceService.class);
        when(indiceService.deflactorParaRubro(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ar.scraper.indices.Deflactor.NEUTRO);
        ScraperConfig config = mock(ScraperConfig.class);
        when(config.getMoneda()).thenReturn("ARS");
        db = TestDatabaseServices.create(dataSource());
        controller = new CatalogoController(service, db.presets(), db.historial(), db.catalogQuery(), db.productos(),
                config, indiceService, new PoliticaStock(Clock.systemUTC(), Duration.ofDays(7)));

        assertThat(db.upsertProductos(List.of(
                producto(EN, 100, true), producto(SIN, 200, false),
                producto(DESC, 300, null), producto(SIN_VIEJO, 400, false))).nuevos()).isEqualTo(4);
        envejecer(SIN_VIEJO);
    }

    private static Product producto(String url, double precio, Boolean enStock) {
        return Product.builder().sitio("Sitio").nombre("Producto " + url).precio(precio).url(url).imagenUrl("img")
                .categoria("Remera").genero("unisex").talles(List.of()).marca("Nike").rubro("indumentaria")
                .enStock(enStock).build();
    }

    /** Pushes the stock check 30 days back: the stored value stays false, the read must not trust it. */
    private void envejecer(String url) throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE productos SET stock_chequeado_at = stock_chequeado_at - interval '30 days' WHERE url = ?")) {
            ps.setString(1, url);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private ResponseEntity<?> pedir(String stock) {
        return controller.data(0, 24, null, null, null, null, null, null, null, null, null, null, "precio_asc",
                null, null, null, null, null, null, null, null, null, null, null, stock);
    }

    private static Set<String> urls(JsonNode productos) {
        return StreamSupport.stream(productos.spliterator(), false)
                .map(p -> p.path("url").asText()).collect(Collectors.toSet());
    }

    private static JsonNode fila(JsonNode productos, String url) {
        for (JsonNode p : productos) if (url.equals(p.path("url").asText())) return p;
        throw new AssertionError("sin fila " + url);
    }

    @Test
    @DisplayName("by default nothing is hidden: rows are flagged with their derived stock")
    void flagsEveryRowByDefault() {
        JsonNode productos = Wire.data(pedir(null)).path("productos");

        assertThat(urls(productos)).containsExactlyInAnyOrder(EN, SIN, DESC, SIN_VIEJO);
        assertThat(fila(productos, EN).path("stock").asText()).isEqualTo("en_stock");
        assertThat(fila(productos, SIN).path("stock").asText()).isEqualTo("sin_stock");
        assertThat(fila(productos, DESC).path("stock").asText()).isEqualTo("desconocido");
        assertThat(fila(productos, SIN_VIEJO).path("stock").asText())
                .as("a stale false is unknown, not sold out").isEqualTo("desconocido");
    }

    @Test
    @DisplayName("rows carry the stock timestamps in UTC ISO-8601 (null when never seen)")
    void rowsCarryTimestamps() {
        JsonNode productos = Wire.data(pedir("todos")).path("productos");

        assertThat(fila(productos, EN).path("stockChequeadoAt").asText()).endsWith("Z");
        assertThat(fila(productos, EN).path("vistoEnStockAt").asText()).endsWith("Z");
        assertThat(fila(productos, SIN).path("vistoEnStockAt").isNull()).isTrue();
        assertThat(fila(productos, DESC).path("stockChequeadoAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("excluir_sin_stock drops only the confirmed sold-out row")
    void excludesOnlyConfirmedSoldOut() {
        JsonNode productos = Wire.data(pedir("excluir_sin_stock")).path("productos");

        assertThat(urls(productos)).containsExactlyInAnyOrder(EN, DESC, SIN_VIEJO);
    }

    @Test
    @DisplayName("keyset mode honours the same filter")
    void keysetHonoursFilter() {
        ResponseEntity<?> resp = controller.data(0, 24, null, null, null, null, null, null, null, null, null, null,
                "precio_asc", null, null, null, null, null, null, null, null, null, null, "", "excluir_sin_stock");

        assertThat(urls(Wire.data(resp).path("productos"))).containsExactlyInAnyOrder(EN, DESC, SIN_VIEJO);
    }

    @Test
    @DisplayName("an unknown value is a 400, not the whole catalog")
    void unknownValueIs400() {
        assertThatThrownBy(() -> pedir("solo_en_stock")).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo("solicitud_invalida");
        });
    }

    @Test
    @DisplayName("the page total counts the filtered set")
    void totalCountsFilteredSet() {
        assertThat(Wire.page(pedir("excluir_sin_stock")).path("total").asLong()).isEqualTo(3);
        assertThat(Wire.page(pedir(null)).path("total").asLong()).isEqualTo(4);
    }
}
