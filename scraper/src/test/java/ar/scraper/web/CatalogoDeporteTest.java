package ar.scraper.web;

import ar.scraper.db.TestDatabaseServices;
import ar.scraper.web.support.Wire;

import ar.scraper.indices.IndiceService;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.api.ApiException;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code deporte} on {@code GET /api/data}: the controller threads the list into the
 * catalog query; the SQL semantics are covered in {@code CatalogExcluirCategoriaTest} (db).
 */
@Epic("REST API")
@Feature("Filtros / Facets")
@Story("Deporte")
@DisplayName("CatalogoController — deporte filter")
class CatalogoDeporteTest extends ar.scraper.db.support.PostgresTestBase {

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

    private Product producto(String url, String deporte) {
        return Product.builder()
                .sitio("Sitio")
                .nombre("Producto " + url)
                .precio(10000.0)
                .precioOriginal(null)
                .url(url)
                .imagenUrl("img")
                .categoria("Remera")
                .genero("unisex")
                .talles(List.of())
                .ml(Product.MlScore.EMPTY)
                .marca(null)
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(SenalFinanciacion.EMPTY)
                .deporte(deporte)
                .build();
    }

    private AggregatedResult resultFor(Product... productos) {
        List<Product> lista = List.of(productos);
        double min = lista.stream().mapToDouble(Product::precio).min().orElse(0);
        double max = lista.stream().mapToDouble(Product::precio).max().orElse(0);
        return new AggregatedResult(lista, Map.of("Sitio", lista.size()), Map.of(),
                ResultAggregator.calcularFacets(lista), min, max);
    }

    private ResponseEntity<?> pedir(String deporte) {
        return controller.data(0, 24, null, null, null, null, null,
                null, null, null, null, null, "precio_asc", null, null, null, null,
                null, null, null, null, null, deporte);
    }

    @Test
    void knownCodeFiltersAndRowsCarryTheirSport() {
        sembrar(producto("https://site.com/river", "futbol"), producto("https://site.com/lisa", ""));

        JsonNode productos = Wire.data(pedir("futbol")).path("productos");

        assertThat(productos).hasSize(1);
        assertThat(productos.get(0).path("url").asText()).isEqualTo("https://site.com/river");
        assertThat(productos.get(0).path("deporte").asText()).isEqualTo("futbol");
    }

    @Test
    void rowWithoutSportSerializesAnEmptyString() {
        sembrar(producto("https://site.com/lisa", ""));

        JsonNode fila = Wire.data(pedir(null)).path("productos").get(0);

        assertThat(fila.has("deporte")).isTrue();
        assertThat(fila.path("deporte").asText()).isEmpty();
    }

    @Test
    void absentAndBlankCodesApplyNoFilter() {
        sembrar(producto("https://site.com/river", "futbol"), producto("https://site.com/lisa", ""));

        assertThat(Wire.data(pedir(null)).path("productos")).hasSize(2);
        assertThat(Wire.data(pedir("  ")).path("productos")).hasSize(2);
    }

    @Test
    void unknownCodeIsABadRequestNotTheWholeCatalog() {
        // A typo ("futbool") used to answer with every product, which reads as "the filter works".
        sembrar(producto("https://site.com/river", "futbol"), producto("https://site.com/lisa", ""));

        assertThatThrownBy(() -> pedir("curling"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.code()).isEqualTo("solicitud_invalida");
                });
    }

    @Test
    void codeIsCaseInsensitive() {
        sembrar(producto("https://site.com/river", "futbol"), producto("https://site.com/lisa", ""));

        assertThat(Wire.data(pedir("FUTBOL")).path("productos")).hasSize(1);
    }
}
