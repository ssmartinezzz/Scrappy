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
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code excluirCategoria} on {@code GET /api/data}: the controller threads the list into the
 * catalog query; the SQL semantics are covered in {@code CatalogExcluirCategoriaTest} (db).
 */
@Epic("REST API")
@Feature("Filtros / Facets")
@Story("Excluir categoria")
@DisplayName("CatalogoController — excluirCategoria filter")
class CatalogoExcluirCategoriaTest extends ar.scraper.db.support.PostgresTestBase {

    private ScraperService service;
    private IndiceService indiceService;
    private ScraperConfig config;
    private DatabaseService db;
    private CatalogoController controller;

    @BeforeEach
    void setUp() {
        wireController();

        when(config.getMoneda()).thenReturn("ARS");
    }

    @Step("Wire CatalogoController with mocked collaborators")
    private void wireController() {
        service          = mock(ScraperService.class);
        indiceService = mock(IndiceService.class);
        when(indiceService.deflactorParaRubro(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ar.scraper.indices.Deflactor.NEUTRO);
        config            = mock(ScraperConfig.class);
        db                = TestDatabaseServices.create(dataSource());
        controller = new CatalogoController(service, db.presets(), db.historial(), db.catalogQuery(), db.productos(), config, indiceService);
    }

    /**
     * `/api/data` lee de la BASE desde `sql-catalog-filtering`, no del snapshot
     * en memoria: sembrar es un upsert real. Stubbear el mock en vez de esto
     * dejaría estos tests verificando que Mockito devuelve lo que se le dijo.
     */
    private final java.util.List<Product> sembrados = new java.util.ArrayList<>();

    private void sembrar(Product... productos) {
        // Acumulativo a propósito: upsertProductos hace soft-delete de todo lo
        // que NO viene en el batch, así que sembrar dos veces desactivaría lo
        // anterior. PostgresTestBase trunca entre tests.
        sembrados.addAll(java.util.List.of(productos));
        db.upsertProductos(java.util.List.copyOf(sembrados));
    }

    private Product producto(String url, String categoria) {
        return Product.builder()
                .sitio("Sitio")
                .nombre("Producto " + url)
                .precio(10000.0)
                .precioOriginal(null)
                .url(url)
                .imagenUrl("img")
                .categoria(categoria)
                .genero("unisex")
                .talles(List.of())
                .ml(Product.MlScore.EMPTY)
                .marca(null)
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(SenalFinanciacion.EMPTY)
                .build();
    }

    private AggregatedResult resultFor(Product... productos) {
        List<Product> lista = List.of(productos);
        double min = lista.stream().mapToDouble(Product::precio).min().orElse(0);
        double max = lista.stream().mapToDouble(Product::precio).max().orElse(0);
        return new AggregatedResult(lista, Map.of("Sitio", lista.size()), Map.of(),
                ResultAggregator.calcularFacets(lista), min, max);
    }

    @Test
    void excludedCategoriesAreDroppedAndTheTotalFollows() {
        sembrar(producto("https://site.com/a", "Remera"),
                producto("https://site.com/b", "Otros"),
                producto("https://site.com/c", "Alimentos"));

        ResponseEntity<?> resp = controller.data(0, 24, null, null, null, null, null,
                null, null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null, List.of("Otros", "Alimentos"));

        JsonNode productos = Wire.data(resp).path("productos");
        assertThat(productos).hasSize(1);
        assertThat(productos.get(0).path("url").asText()).isEqualTo("https://site.com/a");
    }

    @Test
    void absentParamExcludesNothing() {
        sembrar(producto("https://site.com/a", "Remera"), producto("https://site.com/b", "Otros"));

        ResponseEntity<?> resp = controller.data(0, 24, null, null, null, null, null,
                null, null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null);

        assertThat(Wire.data(resp).path("productos")).hasSize(2);
    }
}
