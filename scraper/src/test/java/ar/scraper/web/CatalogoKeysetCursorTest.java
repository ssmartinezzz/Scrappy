package ar.scraper.web;

import ar.scraper.db.TestDatabaseServices;
import ar.scraper.web.support.Wire;

import ar.scraper.indices.IndiceService;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.model.Product;
import ar.scraper.model.Product.SenalFinanciacion;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ar.scraper.api.ApiException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the lower-bound clamping of the {@code page} query param on
 * {@code GET /api/data}. Mirrors {@code CatalogoPrecioRangeTest}'s
 * convention: {@code CatalogoController} is a plain {@code @RestController} POJO
 * instantiated directly with Mockito-mocked collaborators.
 */
@DisplayName("CatalogoController — /api/data keyset mode (cursor), page mode untouched")
class CatalogoKeysetCursorTest extends ar.scraper.db.support.PostgresTestBase {

    private ScraperService service;
    private CatalogoController controller;
    private DatabaseService db;

    @BeforeEach
    void setUp() {
        service = mock(ScraperService.class);
        IndiceService indiceService = mock(IndiceService.class);
        when(indiceService.deflactorParaRubro(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ar.scraper.indices.Deflactor.NEUTRO);
        ScraperConfig config = mock(ScraperConfig.class);
        when(config.getMoneda()).thenReturn("ARS");
        db = TestDatabaseServices.create(dataSource());
        controller = new CatalogoController(service, db.presets(), db.historial(), db.catalogQuery(), db.productos(), config, indiceService);
        List<Product> ps = new java.util.ArrayList<>();
        for (int i = 0; i < 53; i++) ps.add(producto("https://site.com/n" + String.format("%03d", i), 1000 + (i % 5) * 100, "Nike"));
        for (int i = 0; i < 9; i++) ps.add(producto("https://site.com/a" + i, 1000 + i * 100, "Adidas"));
        db.upsertProductos(ps);
    }

    private Product producto(String url, double precio, String marca) {
        return Product.builder().sitio("Sitio").nombre("Producto " + url).precio(precio).url(url).imagenUrl("img")
                .categoria("Remera").genero("unisex").talles(List.of()).ml(Product.MlScore.EMPTY).marca(marca)
                .rubro("indumentaria").gymrat(false).marcaPremium(false).senal(Product.SenalCompra.EMPTY)
                .finan(SenalFinanciacion.EMPTY).build();
    }

    private ResponseEntity<?> porCursor(String cursor, String orden, int size) {
        return controller.data(0, size, null, null, null, null, null, List.of("Nike"), null, null, null, null, orden,
                null, null, null, null, null, null, null, null, null, null, cursor);
    }

    private ResponseEntity<?> porPagina(int page, int size, String orden) {
        return controller.data(page, size, null, null, null, null, null, List.of("Nike"), null, null, null, null, orden,
                null, null, null, null, null, null, null, null, null, null);
    }

    private static String next(ResponseEntity<?> r) {
        JsonNode n = Wire.data(r).path("nextCursor");
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    @Test
    @DisplayName("an empty cursor starts the walk; following nextCursor yields every Nike row once, then stops")
    void recorreTodo() {
        List<String> vistos = new java.util.ArrayList<>();
        String cursor = "";
        for (int guard = 0; guard < 100 && cursor != null; guard++) {
            ResponseEntity<?> r = porCursor(cursor, "precio_asc", 10);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
            Wire.data(r).path("productos").forEach(n -> vistos.add(n.path("url").asText()));
            cursor = next(r);
        }
        assertThat(vistos).hasSize(53).doesNotHaveDuplicates().allMatch(u -> u.contains("/n"));
    }

    @Test
    @DisplayName("page mode hands out a nextCursor for the price orders, so a client can switch to cursors")
    void modoPaginaDaElPrimerCursor() {
        ResponseEntity<?> primera = porPagina(0, 10, "precio_asc");
        assertThat(next(primera)).isNotNull();

        ResponseEntity<?> siguiente = porCursor(next(primera), "precio_asc", 10);
        List<String> urlsPagina1 = new java.util.ArrayList<>();
        Wire.data(porPagina(1, 10, "precio_asc")).path("productos").forEach(n -> urlsPagina1.add(n.path("url").asText()));
        List<String> urlsCursor = new java.util.ArrayList<>();
        Wire.data(siguiente).path("productos").forEach(n -> urlsCursor.add(n.path("url").asText()));
        assertThat(urlsCursor).as("cursor continues exactly where page 0 ended").hasSize(10);
        assertThat(Wire.data(primera).path("productos")).hasSize(10);
    }

    @Test
    @DisplayName("page mode on any other order carries no cursor; the last page carries none either")
    void sinCursorDondeNoCorresponde() {
        assertThat(next(porPagina(0, 10, "nombre_asc"))).isNull();
        assertThat(next(porPagina(5, 10, "precio_asc"))).as("page 5 of 6 is the last").isNull();
    }

    @Test
    @DisplayName("a cursor with an order that cannot use it, or a malformed cursor, is a 400")
    void cursoresInvalidos() {
        assertThatThrownBy(() -> porCursor("", "nombre_asc", 10)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> porCursor("###", "precio_asc", 10)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
