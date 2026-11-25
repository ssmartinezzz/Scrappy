package ar.scraper.aggregator;

import ar.scraper.catalog.ClasificacionBloqueada;
import ar.scraper.catalog.Normalizacion;
import ar.scraper.catalog.ProductPort;
import ar.scraper.ml.CategoriaStatsPort;
import ar.scraper.ml.MlOutputPort;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.ml.MlEnricher;
import ar.scraper.ml.PythonRunner;
import ar.scraper.ml.SenalEnricher;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RED→GREEN coverage for {@link ResultAggregator#renormalizarCatalogo()}'s
 * write reconciliation: the counters of what was written come from the urls the
 * single batch really updated, and a failed batch propagates instead of being counted.
 */
@Epic("Aggregation & Grouping")
@Feature("Renormalize catalog")
@DisplayName("ResultAggregator — renormalizarCatalogo write-reconciliation (one batch)")
class ResultAggregatorRenormalizarTest {

    private NormalizerService    normalizer;
    private PythonRunner         pythonRunner;
    private MlEnricher           mlEnricher;
    private SenalEnricher        senalEnricher;
    private FinanciacionEnricher financiacionEnricher;
    private MlOutputPort             mlOutput;
    private CategoriaStatsPort       categoriaStats;
    private ProductPort          productos;
    private ResultAggregator     aggregator;

    @BeforeEach
    void setUp() {
        wireAggregator();
    }

    @Step("Wire ResultAggregator with mocked ML persistence ports")
    private void wireAggregator() {
        normalizer           = mock(NormalizerService.class);
        pythonRunner         = mock(PythonRunner.class);
        mlEnricher            = mock(MlEnricher.class);
        senalEnricher         = mock(SenalEnricher.class);
        financiacionEnricher  = mock(FinanciacionEnricher.class);
        mlOutput                           = mock(MlOutputPort.class);
        categoriaStats                     = mock(CategoriaStatsPort.class);
        productos             = mock(ProductPort.class);

        aggregator = new ResultAggregator(
                normalizer, pythonRunner, mlEnricher, senalEnricher, financiacionEnricher,
                mlOutput, categoriaStats,
                productos);
    }

    private Product producto(String url, String categoria) {
        return Product.builder()
                .sitio("TestSite")
                .nombre("Producto " + url)
                .precio(1000)
                .precioOriginal(null)
                .url(url)
                .imagenUrl("")
                .categoria(categoria)
                .genero("")
                .talles(List.of())
                .build();
    }

    private Product conRubro(String url, String categoria, String rubro) {
        return producto(url, categoria).toBuilder().rubro(rubro).build();
    }

    @SuppressWarnings("unchecked")
    private List<Normalizacion> escrito() {
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(productos).actualizarNormalizaciones(captor.capture());
        return (List<Normalizacion>) captor.getValue();
    }

    @Test
    @DisplayName("escrituras* reflect the urls the batch really updated, not the intended diff")
    void reconcilesIntendedVsApplied() {
        // p1 updated; p2 not updated and not locked (the row is gone)
        when(productos.cargarProductos()).thenReturn(List.of(
                producto("http://test.com/1", "Zapatilla"), producto("http://test.com/2", "Zapatilla")));
        when(normalizer.normalizar(anyList())).thenReturn(List.of(
                producto("http://test.com/1", "Calzado Deportivo"), producto("http://test.com/2", "Calzado Deportivo")));
        when(productos.actualizarNormalizaciones(anyList())).thenReturn(Set.of("http://test.com/1"));

        Map<String, Integer> resultado = aggregator.renormalizarCatalogo();

        assertThat(resultado.get("totalRevisados")).isEqualTo(2);
        assertThat(resultado.get("categoriaCambiada")).isEqualTo(2);
        assertThat(resultado.get("escriturasIntentadas")).isEqualTo(2);
        assertThat(resultado.get("escriturasAplicadas")).isEqualTo(1);
        assertThat(resultado.get("escriturasFallidas")).isEqualTo(1);
        assertThat(escrito()).extracting(Normalizacion::url)
                .containsExactly("http://test.com/1", "http://test.com/2");
    }

    @Test
    @DisplayName("a failed batch propagates: nothing is counted as partially written")
    void aFailedBatchPropagates() {
        when(productos.cargarProductos()).thenReturn(List.of(producto("http://test.com/3", "Zapatilla")));
        when(normalizer.normalizar(anyList())).thenReturn(List.of(producto("http://test.com/3", "Calzado Deportivo")));
        when(productos.actualizarNormalizaciones(anyList())).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> aggregator.renormalizarCatalogo())
                .isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }

    @Test
    @DisplayName("manual-classification-lock: locked URLs never enter the batch, are counted separately, never in escriturasFallidas")
    void lockedUrlsAreSkippedFromWritesAndCountedSeparately() {
        when(productos.cargarProductos()).thenReturn(List.of(
                producto("http://test.com/1", "Zapatilla"), producto("http://test.com/4", "Zapatilla")));
        when(normalizer.normalizar(anyList())).thenReturn(List.of(
                producto("http://test.com/1", "Calzado Deportivo"), producto("http://test.com/4", "Calzado Deportivo")));
        when(productos.cargarClasificacionBloqueada()).thenReturn(
                Map.of("http://test.com/4", new ClasificacionBloqueada("Zapatilla", "", "", "", "indumentaria")));
        when(productos.actualizarNormalizaciones(anyList())).thenReturn(Set.of("http://test.com/1"));

        Map<String, Integer> resultado = aggregator.renormalizarCatalogo();

        assertThat(resultado.get("totalRevisados")).isEqualTo(2);
        assertThat(resultado.get("categoriaCambiada")).isEqualTo(2);
        assertThat(resultado.get("escriturasIntentadas")).isEqualTo(1);
        assertThat(resultado.get("escriturasAplicadas")).isEqualTo(1);
        assertThat(resultado.get("escriturasFallidas")).isEqualTo(0);
        assertThat(resultado.get("escriturasOmitidasPorBloqueo")).isEqualTo(1);
        assertThat(escrito()).extracting(Normalizacion::url).containsExactly("http://test.com/1");
    }

    @Test
    @DisplayName("a product locked AFTER the entry snapshot is attributed by a live check, not counted as a failure")
    void productLockedAfterSnapshotIsCountedAsOmittedNotFailed() {
        when(productos.cargarProductos()).thenReturn(List.of(producto("http://test.com/5", "Zapatilla")));
        when(normalizer.normalizar(anyList())).thenReturn(List.of(producto("http://test.com/5", "Calzado Deportivo")));
        when(productos.cargarClasificacionBloqueada()).thenReturn(Map.of());
        when(productos.actualizarNormalizaciones(anyList())).thenReturn(Set.of());
        when(productos.estaBloqueado("http://test.com/5")).thenReturn(true);

        Map<String, Integer> resultado = aggregator.renormalizarCatalogo();

        assertThat(resultado.get("escriturasIntentadas")).isEqualTo(1);
        assertThat(resultado.get("escriturasAplicadas")).isEqualTo(0);
        assertThat(resultado.get("escriturasFallidas")).isEqualTo(0);
        assertThat(resultado.get("escriturasOmitidasPorBloqueo")).isEqualTo(1);
    }

    @Test
    @DisplayName("a rubro change alone is a change: it is counted and written with the normalized rubro")
    void aRubroChangeIsWritten() {
        when(productos.cargarProductos()).thenReturn(List.of(conRubro("http://test.com/6", "Heladera", "hogar")));
        when(normalizer.normalizar(anyList())).thenReturn(List.of(conRubro("http://test.com/6", "Heladera", "electrodomesticos")));
        when(productos.actualizarNormalizaciones(anyList())).thenReturn(Set.of("http://test.com/6"));

        Map<String, Integer> resultado = aggregator.renormalizarCatalogo();

        assertThat(resultado.get("rubroCambiado")).isEqualTo(1);
        assertThat(resultado.get("categoriaCambiada")).isEqualTo(0);
        assertThat(resultado.get("escriturasAplicadas")).isEqualTo(1);
        assertThat(escrito()).singleElement().satisfies(f -> {
            assertThat(f.rubro()).isEqualTo("electrodomesticos");
            assertThat(f.talles()).as("sizes did not change: left alone").isNull();
        });
    }

    @Test
    @DisplayName("no changes detected -> zero escrituras*, an empty batch")
    void noChangesDetected_zeroEscrituras() {
        when(productos.cargarProductos()).thenReturn(List.of(producto("http://test.com/unchanged", "Zapatilla")));
        when(normalizer.normalizar(anyList())).thenReturn(List.of(producto("http://test.com/unchanged", "Zapatilla")));
        when(productos.actualizarNormalizaciones(anyList())).thenReturn(Set.of());

        Map<String, Integer> resultado = aggregator.renormalizarCatalogo();

        assertThat(resultado.get("totalRevisados")).isEqualTo(1);
        assertThat(resultado.get("rubroCambiado")).isEqualTo(0);
        assertThat(resultado.get("escriturasIntentadas")).isEqualTo(0);
        assertThat(resultado.get("escriturasAplicadas")).isEqualTo(0);
        assertThat(resultado.get("escriturasFallidas")).isEqualTo(0);
        assertThat(escrito()).isEmpty();
    }
}
