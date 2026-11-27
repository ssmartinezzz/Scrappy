package ar.scraper.aggregator;

import ar.scraper.catalog.ProductPort;
import ar.scraper.ml.CategoriaStatsPort;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.ml.MlEnricher;
import ar.scraper.ml.MlOutputPort;
import ar.scraper.ml.PythonRunner;
import ar.scraper.ml.SenalEnricher;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Epic("Aggregation & Grouping")
@Feature("Sticky storefront name")
@DisplayName("ResultAggregator.conNombreGuardado — a provisional name yields to the stored one")
class ResultAggregatorNombreGuardadoTest {

    private ProductPort productos;
    private ResultAggregator aggregator;

    @BeforeEach
    void setUp() {
        productos = mock(ProductPort.class);
        aggregator = new ResultAggregator(mock(NormalizerService.class), mock(PythonRunner.class),
                mock(MlEnricher.class), mock(SenalEnricher.class), mock(FinanciacionEnricher.class),
                mock(MlOutputPort.class), mock(CategoriaStatsPort.class), productos);
    }

    private static Product producto(String url, String nombre, boolean provisional) {
        return Product.builder().sitio("fravega").nombre(nombre).precio(5000).url(url)
                .nombreProvisional(provisional).build();
    }

    @Test
    @DisplayName("a flagged row takes the stored name; unflagged rows are untouched")
    void tomaElNombreGuardado() {
        when(productos.cargarProductosPorUrl(anyCollection()))
                .thenReturn(List.of(producto("u/a-1/p", "Heladera Gafa Full Name", false)));

        List<Product> out = aggregator.conNombreGuardado(List.of(
                producto("u/a-1/p", "HELA. GAFA", true),
                producto("u/b-2/p", "Titulo de tienda", false)));

        assertThat(out).extracting(Product::nombre).containsExactly("Heladera Gafa Full Name", "Titulo de tienda");
        assertThat(out).noneMatch(Product::nombreProvisional);
    }

    @Test
    @DisplayName("no stored row: the API name stays")
    void sinFilaGuardada() {
        when(productos.cargarProductosPorUrl(anyCollection())).thenReturn(List.of());

        List<Product> out = aggregator.conNombreGuardado(List.of(producto("u/a-1/p", "HELA. GAFA", true)));

        assertThat(out).extracting(Product::nombre).containsExactly("HELA. GAFA");
    }

    @Test
    @DisplayName("the port fails: names stay as they are and nothing is thrown")
    void falloDelPuerto() {
        when(productos.cargarProductosPorUrl(anyCollection())).thenThrow(new IllegalStateException("db down"));

        List<Product> out = aggregator.conNombreGuardado(List.of(producto("u/a-1/p", "HELA. GAFA", true)));

        assertThat(out).extracting(Product::nombre).containsExactly("HELA. GAFA");
    }

    @Test
    @DisplayName("no flagged row: the port is not even asked")
    void sinMarcadas() {
        List<Product> in = List.of(producto("u/a-1/p", "Titulo", false));

        assertThat(aggregator.conNombreGuardado(in)).isSameAs(in);
        verify(productos, never()).cargarProductosPorUrl(anyCollection());
    }
}
