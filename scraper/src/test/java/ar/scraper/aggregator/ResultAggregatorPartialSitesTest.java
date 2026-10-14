package ar.scraper.aggregator;

import ar.scraper.catalog.ProductPort;
import ar.scraper.ml.CategoriaStatsPort;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.ml.MlEnricher;
import ar.scraper.ml.MlOutputPort;
import ar.scraper.ml.PythonRunner;
import ar.scraper.ml.SenalEnricher;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Epic("Aggregation & Grouping")
@Feature("Partial sites")
@DisplayName("ResultAggregator — tells the upsert which sites came back partial")
class ResultAggregatorPartialSitesTest {

    private static Product producto(String sitio, String url) {
        return Product.builder().sitio(sitio).nombre("Remera").precio(1500).url(url)
                .imagenUrl("").categoria("Remera").genero("").talles(List.of()).build();
    }

    @Test
    @DisplayName("a site with products and an error is partial; a clean one and a failed one are not")
    @SuppressWarnings("unchecked")
    void onlyProductsPlusErrorIsPartial() {
        NormalizerService normalizer = mock(NormalizerService.class);
        PythonRunner pythonRunner = mock(PythonRunner.class);
        MlEnricher mlEnricher = mock(MlEnricher.class);
        SenalEnricher senalEnricher = mock(SenalEnricher.class);
        FinanciacionEnricher financiacionEnricher = mock(FinanciacionEnricher.class);
        ProductPort productos = mock(ProductPort.class);
        when(normalizer.normalizar(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(mlEnricher.serializarProductos(anyList())).thenReturn("[]");
        when(pythonRunner.ejecutar(anyString())).thenReturn(null);
        when(mlEnricher.enriquecer(anyList(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(senalEnricher.enriquecer(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(financiacionEnricher.enriquecer(anyList())).thenAnswer(inv -> inv.getArgument(0));
        ResultAggregator aggregator = new ResultAggregator(normalizer, pythonRunner, mlEnricher, senalEnricher,
                financiacionEnricher, mock(MlOutputPort.class), mock(CategoriaStatsPort.class), productos);

        aggregator.agregar(List.of(
                new ScrapeResult("Parcial", List.of(producto("Parcial", "http://p/1")), "paginación cortada en p3: x", 1),
                new ScrapeResult("Completo", List.of(producto("Completo", "http://c/1")), null, 1),
                new ScrapeResult("Roto", List.of(), "boom", 1)), false);

        ArgumentCaptor<Set<String>> parciales = ArgumentCaptor.forClass(Set.class);
        verify(productos).upsertProductos(anyList(), any(), parciales.capture());
        assertThat(parciales.getValue()).containsExactly("Parcial");
    }
}
