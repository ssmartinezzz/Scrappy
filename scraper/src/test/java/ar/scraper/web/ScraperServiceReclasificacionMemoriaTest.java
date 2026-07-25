package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScraperService#actualizarProductoEnMemoria}, the
 * in-memory catalog patch applied after a confirmed agent reclassification
 * writes to Postgres.
 *
 * <p>Why this exists: {@code /api/data} and {@code /api/mejores} serve from
 * {@code lastResult}, not from the database — that snapshot is only rebuilt at
 * startup or after a scrape. Without this patch a reclassification persisted by
 * {@code POST /api/agent/apply} stays invisible in the UI until the backend
 * restarts, which is the same trap {@link ScraperService#eliminarProductoDeMemoria}
 * already solves for manual soft-deletes.</p>
 */
@Epic("Outfit Orchestration")
@Feature("Scraper Orchestration")
@Story("Reclasificación en memoria")
@DisplayName("ScraperService — patch del catálogo en memoria tras reclasificar")
class ScraperServiceReclasificacionMemoriaTest {

    private static final String URL = "https://site.com/epic-shirt";

    private Product producto(String url, String categoria, String marca,
                             String genero, String subCategoria) {
        return new Product("Sitio", "Epic Shirt", 1000, null, url, "",
                categoria, genero, List.of("M"), Product.MlScore.EMPTY, marca,
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1, subCategoria, null);
    }

    private ScraperService serviceCon(Product... productos) {
        ScraperConfig config = Mockito.mock(ScraperConfig.class);
        ResultAggregator aggregator = Mockito.mock(ResultAggregator.class);
        DatabaseService db = Mockito.mock(DatabaseService.class);
        ScraperService service = new ScraperService(config, aggregator, db);
        List<Product> lista = List.of(productos);
        service.setLastResultParaTest(new AggregatedResult(
                lista, java.util.Map.of("Sitio", lista.size()), java.util.Map.of(),
                ResultAggregator.calcularFacets(lista), 1000, 1000));
        return service;
    }

    @Test
    @DisplayName("reemplaza la clasificación del producto reclasificado")
    void patchesTheReclassifiedProduct() {
        ScraperService service = serviceCon(
                producto(URL, "Camisa", "Genérica", "unisex", "Casual"));

        service.actualizarProductoEnMemoria(URL, "Remera", "Monkyforce", "hombre", "Entrenamiento");

        Product patched = service.getLastResult().productos().get(0);
        assertThat(patched.categoria()).isEqualTo("Remera");
        assertThat(patched.marca()).isEqualTo("Monkyforce");
        assertThat(patched.genero()).isEqualTo("hombre");
        assertThat(patched.subCategoria()).isEqualTo("Entrenamiento");
    }

    @Test
    @DisplayName("preserva los campos que la reclasificación no toca")
    void preservesUntouchedFields() {
        ScraperService service = serviceCon(
                producto(URL, "Camisa", "Genérica", "unisex", "Casual"));

        service.actualizarProductoEnMemoria(URL, "Remera", "Monkyforce", "hombre", "Entrenamiento");

        Product patched = service.getLastResult().productos().get(0);
        assertThat(patched.nombre()).isEqualTo("Epic Shirt");
        assertThat(patched.precio()).isEqualTo(1000);
        assertThat(patched.url()).isEqualTo(URL);
        assertThat(patched.talles()).containsExactly("M");
        assertThat(patched.cantidadUnidades()).isEqualTo(1);
    }

    @Test
    @DisplayName("no toca los demás productos del catálogo")
    void leavesOtherProductsAlone() {
        ScraperService service = serviceCon(
                producto(URL, "Camisa", "Genérica", "unisex", "Casual"),
                producto("https://site.com/otro", "Pantalón", "Otra", "mujer", "Cargo"));

        service.actualizarProductoEnMemoria(URL, "Remera", "Monkyforce", "hombre", "Entrenamiento");

        List<Product> productos = service.getLastResult().productos();
        assertThat(productos).hasSize(2);
        Product intacto = productos.stream()
                .filter(p -> "https://site.com/otro".equals(p.url()))
                .findFirst().orElseThrow();
        assertThat(intacto.categoria()).isEqualTo("Pantalón");
        assertThat(intacto.marca()).isEqualTo("Otra");
    }

    @Test
    @DisplayName("es un no-op si no hay catálogo cargado o la url es nula")
    void isNoOpWithoutCatalogOrUrl() {
        ScraperConfig config = Mockito.mock(ScraperConfig.class);
        ResultAggregator aggregator = Mockito.mock(ResultAggregator.class);
        DatabaseService db = Mockito.mock(DatabaseService.class);
        ScraperService sinCatalogo = new ScraperService(config, aggregator, db);

        sinCatalogo.actualizarProductoEnMemoria(URL, "Remera", "M", "hombre", "Entrenamiento");
        assertThat(sinCatalogo.getLastResult()).isNull();

        ScraperService conCatalogo = serviceCon(
                producto(URL, "Camisa", "Genérica", "unisex", "Casual"));
        conCatalogo.actualizarProductoEnMemoria(null, "Remera", "M", "hombre", "Entrenamiento");
        assertThat(conCatalogo.getLastResult().productos().get(0).categoria()).isEqualTo("Camisa");
    }

    @Test
    @DisplayName("recalcula las facetas para que los contadores del filtro no mientan")
    void recomputesFacetsSoFilterCountsStayHonest() {
        ScraperService service = serviceCon(
                producto(URL, "Camisa", "Genérica", "unisex", "Casual"));

        service.actualizarProductoEnMemoria(URL, "Remera", "Monkyforce", "hombre", "Entrenamiento");

        // Reusing the previous facets (as eliminarProductoDeMemoria does) would
        // leave the catalog filter offering "Camisa" and hiding "Remera".
        assertThat(service.getLastResult().facets().categorias()).containsKey("Remera");
        assertThat(service.getLastResult().facets().categorias()).doesNotContainKey("Camisa");
    }

    @Test
    @DisplayName("un valor en blanco no borra el dato que ya estaba")
    void blankValuesDoNotWipeExistingData() {
        ScraperService service = serviceCon(
                producto(URL, "Camisa", "Genérica", "unisex", "Casual"));

        service.actualizarProductoEnMemoria(URL, "Remera", "", null, "  ");

        Product patched = service.getLastResult().productos().get(0);
        assertThat(patched.categoria()).isEqualTo("Remera");
        assertThat(patched.marca()).isEqualTo("Genérica");
        assertThat(patched.genero()).isEqualTo("unisex");
        assertThat(patched.subCategoria()).isEqualTo("Casual");
    }
}
