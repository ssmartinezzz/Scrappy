package ar.scraper.aggregator;

import ar.scraper.catalog.ProductPort;
import ar.scraper.ml.CategoriaStatsPort;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.catalog.HistorialPort;
import ar.scraper.ml.MlEnricher;
import ar.scraper.ml.MlOutputPort;
import ar.scraper.ml.PythonRunner;
import ar.scraper.ml.SenalEnricher;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import ar.scraper.model.promo.Alcance;
import ar.scraper.model.promo.PromoFinanciacion;
import ar.scraper.model.promo.PromosDeCorrida;
import ar.scraper.model.promo.TipoMedio;
import ar.scraper.model.promo.TipoPromo;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Epic("Aggregation & Grouping")
@Feature("Promos")
@DisplayName("Product promos survive scrape result -> normalizer -> ML -> señal/financiación -> upsert")
class ProductPromosSurvivePipelineTest {

    private static final PromoFinanciacion CUOTAS = new PromoFinanciacion("Visa", TipoMedio.TARJETA,
            TipoPromo.CUOTAS_SIN_INTERES, 12, null, Alcance.PRODUCTO, null, null, null);

    @Test
    @DisplayName("the real normalizer and enrichers hand the upsert the promos the page put on the product")
    @SuppressWarnings("unchecked")
    void promosReachTheUpsert() throws Exception {
        Product p = Product.builder().sitio("Sporting").nombre("Zapatillas Running Galaxy 7").precio(89999)
                .url("https://www.sporting.com.ar/galaxy-7/p").imagenUrl("").categoria("Zapatillas")
                .genero("hombre").talles(List.of()).promos(List.of(CUOTAS)).build();

        PythonRunner python = mock(PythonRunner.class);
        when(python.ejecutar(anyString())).thenReturn(new ObjectMapper().readTree(
                "{\"scores\":{\"https://www.sporting.com.ar/galaxy-7/p\":{\"score\":0.7}}}"));
        HistorialPort historial = mock(HistorialPort.class);
        when(historial.getHistorialPrecios(anyList())).thenReturn(Map.of());
        var indices = mock(ar.scraper.indices.IndiceService.class);
        ProductPort productos = mock(ProductPort.class);
        ResultAggregator aggregator = new ResultAggregator(NormalizerServiceTestFactory.create(), python,
                new MlEnricher(), new SenalEnricher(historial, indices),
                new FinanciacionEnricher(mock(ar.scraper.financiacion.PresetPort.class), indices),
                mock(MlOutputPort.class), mock(CategoriaStatsPort.class), productos);

        aggregator.agregar(List.of(new ScrapeResult("Sporting", List.of(p), null, 1)), false);

        ArgumentCaptor<List<Product>> enviados = ArgumentCaptor.forClass(List.class);
        verify(productos).upsertProductos(enviados.capture(), any(), any(Set.class), any(PromosDeCorrida.class));
        assertThat(enviados.getValue()).singleElement().satisfies(u -> assertThat(u.promos()).containsExactly(CUOTAS));
    }
}
