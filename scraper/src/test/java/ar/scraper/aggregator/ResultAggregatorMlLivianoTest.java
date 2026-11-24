package ar.scraper.aggregator;

import ar.scraper.catalog.HistorialPort;
import ar.scraper.catalog.ProductPort;
import ar.scraper.ml.CategoriaStatsPort;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.ml.MlEnricher;
import ar.scraper.ml.MlOutputPort;
import ar.scraper.ml.PythonRunner;
import ar.scraper.ml.SenalEnricher;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Epic("Aggregation & Grouping")
@Feature("ML output")
@Story("The aggregation applies the scores and keeps the summary")
@DisplayName("ResultAggregator — per-product scores are applied, then only the summary is kept")
class ResultAggregatorMlLivianoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL_A = "https://s.com/a";
    private static final String URL_B = "https://s.com/b";

    private PythonRunner python;
    private ProductPort productos;
    private MlOutputPort mlOutput;
    private CategoriaStatsPort categoriaStats;
    private ResultAggregator aggregator;

    @BeforeEach
    void setUp() {
        python = mock(PythonRunner.class);
        productos = mock(ProductPort.class);
        mlOutput = mock(MlOutputPort.class);
        categoriaStats = mock(CategoriaStatsPort.class);
        HistorialPort historial = mock(HistorialPort.class);
        when(historial.getHistorialPrecios(anyList())).thenReturn(Map.of());
        var indices = mock(ar.scraper.indices.IndiceService.class);
        aggregator = new ResultAggregator(NormalizerServiceTestFactory.create(), python, new MlEnricher(),
                new SenalEnricher(historial, indices),
                new FinanciacionEnricher(mock(ar.scraper.financiacion.PresetPort.class), indices),
                mlOutput, categoriaStats, productos);
    }

    private static Product producto(String url, String nombre, double precio) {
        return Product.builder().sitio("Sitio").nombre(nombre).precio(precio).url(url).imagenUrl("")
                .categoria("Remera").genero("hombre").talles(List.of()).build();
    }

    private JsonNode salida() throws Exception {
        return MAPPER.readTree("""
                {"scores":{"%s":{"composite":88,"pctil":80,"badges":["oferta"],"categoriaML":"Buzo","catMLConf":0.95},
                           "%s":{"composite":12,"pctil":10,"badges":[]}},
                 "categoriaStats":{"Remera":{"n":2}},
                 "tendencias":{"fecha":"2026-11-24","totalProductos":2}}
                """.formatted(URL_A, URL_B));
    }

    private List<Product> agregar() {
        return aggregator.agregar(List.of(new ScrapeResult("Sitio", List.of(
                producto(URL_A, "Remera Lisa A", 100), producto(URL_B, "Remera Lisa B", 200)), null, 1)),
                false).productos();
    }

    @Test
    @DisplayName("the scores still reach the products: score, badges and the refined category")
    void scoresAreStillApplied() throws Exception {
        when(python.ejecutarEscribiendo(any())).thenReturn(salida());

        List<Product> resultado = agregar();

        assertThat(resultado).extracting(Product::url).containsExactly(URL_A, URL_B);
        assertThat(resultado.get(0).ml().scoreP()).isEqualTo(88);
        assertThat(resultado.get(0).ml().badges()).containsExactly("oferta");
        assertThat(resultado.get(0).categoria()).isEqualTo("Buzo");
        assertThat(resultado.get(1).categoria()).isEqualTo("Remera");
        verify(productos).actualizarCategoria(URL_A, "Buzo");
        verify(productos, never()).actualizarCategoria(org.mockito.ArgumentMatchers.eq(URL_B), any());
        assertThat(aggregator.getLastCatRefinadas()).isEqualTo(1);
    }

    @Test
    @DisplayName("what is retained for /api/tendencias is the summary: tendencias intact, scores a non-empty total")
    void retainedOutputIsTheSummary() throws Exception {
        when(python.ejecutarEscribiendo(any())).thenReturn(salida());

        agregar();

        JsonNode retenido = aggregator.getLastMlOutput();
        assertThat(retenido.path("tendencias")).isEqualTo(salida().path("tendencias"));
        assertThat(retenido.path("scores").isObject()).isTrue();
        assertThat(retenido.path("scores").isEmpty()).isFalse();
        assertThat(retenido.path("scores").has(URL_A)).isFalse();
    }

    @Test
    @DisplayName("what is persisted and what feeds the category stats is that summary, with the stats intact")
    void persistedOutputIsTheSummary() throws Exception {
        when(python.ejecutarEscribiendo(any())).thenReturn(salida());

        agregar();

        ArgumentCaptor<JsonNode> guardado = ArgumentCaptor.forClass(JsonNode.class);
        verify(mlOutput).guardarMlOutput(guardado.capture());
        assertThat(guardado.getValue().path("tendencias")).isEqualTo(salida().path("tendencias"));
        assertThat(guardado.getValue().path("scores").has(URL_A)).isFalse();
        verify(categoriaStats).guardarCategoriaStats(salida().path("categoriaStats"));
    }

    @Test
    @DisplayName("when the pipeline did not run nothing is retained or persisted, and the products pass through")
    void notRunKeepsTheContract() {
        when(python.ejecutarEscribiendo(any())).thenReturn(null);

        List<Product> resultado = agregar();

        assertThat(resultado).hasSize(2);
        assertThat(aggregator.getLastMlOutput()).isNull();
        verify(mlOutput).guardarMlOutput(null);
        verify(categoriaStats, never()).guardarCategoriaStats(any());
        verify(productos, never()).actualizarCategoria(any(), any());
    }

    @Test
    @DisplayName("setLastMlOutput keeps the summary of whatever it is given")
    void setterKeepsTheSummary() throws Exception {
        aggregator.setLastMlOutput(salida());

        assertThat(aggregator.getLastMlOutput().path("scores").has(URL_A)).isFalse();
        assertThat(aggregator.getLastMlOutput().path("tendencias")).isEqualTo(salida().path("tendencias"));
    }
}
