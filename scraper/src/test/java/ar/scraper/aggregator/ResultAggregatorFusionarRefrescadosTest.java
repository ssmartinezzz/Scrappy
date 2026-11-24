package ar.scraper.aggregator;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Epic("Aggregation & Grouping")
@Feature("Per-site refresh")
@DisplayName("ResultAggregator.fusionarRefrescados — merge equals full reload + fromDBParcial")
class ResultAggregatorFusionarRefrescadosTest {

    private static final Product.SenalCompra VIEJA = new Product.SenalCompra("vieja", 70, null);
    private static final Product.SenalCompra FRESCA = new Product.SenalCompra("fresca", 10, null);

    private SenalEnricher senal;
    private ResultAggregator aggregator;

    @BeforeEach
    void setUp() {
        senal = mock(SenalEnricher.class);
        FinanciacionEnricher finan = mock(FinanciacionEnricher.class);
        when(senal.enriquecer(anyList())).thenAnswer(inv -> ((List<Product>) inv.getArgument(0)).stream()
                .map(p -> p.toBuilder().senal(FRESCA).build()).toList());
        when(finan.enriquecer(anyList())).thenAnswer(inv -> inv.getArgument(0));
        aggregator = new ResultAggregator(mock(NormalizerService.class), mock(PythonRunner.class),
                mock(MlEnricher.class), senal, finan, mock(MlOutputPort.class),
                mock(CategoriaStatsPort.class), mock(ProductPort.class));
    }

    private static Product p(String sitio, String url, double precio, Product.SenalCompra senal) {
        return Product.builder().sitio(sitio).nombre("N " + url).precio(precio).url(url)
                .imagenUrl("").categoria("Remera").genero("").talles(List.of()).senal(senal).build();
    }

    private AggregatedResult previo(Product... ps) {
        return new AggregatedResult(List.of(ps), java.util.Map.of(), java.util.Map.of(),
                ResultAggregator.calcularFacets(List.of(ps)), 0, 0);
    }

    @Test
    @DisplayName("price change, new product, deactivated product and a blank URL: same snapshot as the full path")
    void equivalente() {
        Product a = p("X", "u/a", 100, VIEJA), b = p("S", "u/b", 200, VIEJA), c = p("X", "u/c", 300, VIEJA);
        Product f = p("S", "u/f", 350, VIEJA), d = p("X", "u/d", 400, VIEJA), sinUrl = p("X", "", 10, VIEJA);
        AggregatedResult previo = previo(sinUrl, a, b, c, f, d);
        Set<String> urls = Set.of("u/b", "u/e", "u/f");

        // The database now: b re-priced, e new, f gone; the rest as before. cargarProductos order.
        List<Product> bd = new ArrayList<>(List.of(p("X", "u/a", 100, Product.SenalCompra.EMPTY),
                p("S", "u/b", 450, Product.SenalCompra.EMPTY), p("X", "u/c", 300, Product.SenalCompra.EMPTY),
                p("X", "u/d", 400, Product.SenalCompra.EMPTY), p("S", "u/e", 50, Product.SenalCompra.EMPTY)));
        bd.sort(Comparator.comparingDouble(Product::precio));
        List<Product> frescos = bd.stream().filter(x -> urls.contains(x.url())).toList();

        AggregatedResult porMerge = aggregator.fusionarRefrescados(previo, urls, frescos);
        AggregatedResult porReload = aggregator.fromDBParcial(bd, previo, urls);

        assertThat(porMerge.productos()).isEqualTo(porReload.productos());
        assertThat(porMerge.productos()).extracting(Product::url)
                .containsExactly("u/e", "u/a", "u/c", "u/d", "u/b");
        assertThat(porMerge.conteoPorSitio()).isEqualTo(porReload.conteoPorSitio());
        assertThat(porMerge.facets()).isEqualTo(porReload.facets());
        assertThat(porMerge.minPrecio()).isEqualTo(50);
        assertThat(porMerge.maxPrecio()).isEqualTo(450);
    }

    @Test
    @DisplayName("untouched products are the same instances; refreshed ones carry the fresh signal")
    void instanciasReusadas() {
        Product a = p("X", "u/a", 100, VIEJA), b = p("S", "u/b", 200, VIEJA);

        AggregatedResult r = aggregator.fusionarRefrescados(previo(a, b), Set.of("u/b"),
                List.of(p("S", "u/b", 200, Product.SenalCompra.EMPTY)));

        assertThat(r.productos().get(0)).isSameAs(a);
        assertThat(r.productos().get(1).senal()).isEqualTo(FRESCA);
    }

    @Test
    @DisplayName("the previous snapshot is not mutated")
    void previoIntacto() {
        Product a = p("X", "u/a", 100, VIEJA), b = p("S", "u/b", 200, VIEJA);
        AggregatedResult previo = previo(a, b);
        List<Product> antes = List.copyOf(previo.productos());

        aggregator.fusionarRefrescados(previo, Set.of("u/b"), List.of(p("S", "u/b", 50, Product.SenalCompra.EMPTY)));

        assertThat(previo.productos()).containsExactlyElementsOf(antes);
        assertThat(previo.productos().get(1).precio()).isEqualTo(200);
    }

    @Test
    @DisplayName("a refreshed URL with no fresh row (all inactive) leaves the snapshot")
    void todoDesactivado() {
        Product a = p("X", "u/a", 100, VIEJA), b = p("S", "u/b", 200, VIEJA);

        AggregatedResult r = aggregator.fusionarRefrescados(previo(a, b), Set.of("u/b"), List.of());

        assertThat(r.productos()).containsExactly(a);
        assertThat(r.conteoPorSitio()).containsEntry("X", 1).doesNotContainKey("S");
    }

    @Test
    @DisplayName("equal prices keep the previous product ahead of the fresh one, and nothing is lost")
    void empates() {
        Product a = p("X", "u/a", 100, VIEJA);

        AggregatedResult r = aggregator.fusionarRefrescados(previo(a), Set.of("u/n"),
                List.of(p("S", "u/n", 100, Product.SenalCompra.EMPTY)));

        assertThat(r.productos()).extracting(Product::url).containsExactly("u/a", "u/n");
    }

    @Test
    @DisplayName("an enricher that answers with another size throws, so the caller keeps its snapshot")
    void enricherDescuadrado() {
        when(senal.enriquecer(anyList())).thenReturn(List.of());

        assertThatThrownBy(() -> aggregator.fusionarRefrescados(previo(p("X", "u/a", 1, VIEJA)), Set.of("u/n"),
                List.of(p("S", "u/n", 100, Product.SenalCompra.EMPTY))))
                .isInstanceOf(IllegalStateException.class);
    }
}
