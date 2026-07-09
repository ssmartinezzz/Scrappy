package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.model.Product;
import ar.scraper.model.Product.SenalFinanciacion;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScraperService#recomputarFinanciacion(ResultAggregator)},
 * the synchronous in-memory catalog swap triggered by preset activate/edit (ADR-5
 * of financing-buy-signal design — no async/background job, since it is cheap
 * arithmetic over the already-loaded catalog).
 *
 * <p>{@link ResultAggregator} and {@link DatabaseService} are mocked: the only
 * thing under test here is that {@code ScraperService} correctly reads its
 * current in-memory catalog, re-enriches it via the aggregator's financing
 * enricher, and replaces {@code lastResult} in place — without re-running the
 * full ML/normalize pipeline (that part is exercised by
 * {@code FinanciacionEnricherTest} and the {@code ResultAggregator} wiring
 * tests already covered elsewhere).</p>
 */
@Epic("Outfit Orchestration")
@Feature("Scraper Orchestration")
@Story("Financiación wiring")
@DisplayName("ScraperService — Financiación wiring")
class ScraperServiceFinanciacionTest {

    private Product producto(String url, double precio, SenalFinanciacion finan) {
        return new Product("Sitio", "p-" + url, precio, null, url, "",
                "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, finan);
    }

    @Test
    void recomputeReplacesProductListWithFreshlyEnrichedFinancingSignal() {
        ScraperConfig config = Mockito.mock(ScraperConfig.class);
        ResultAggregator aggregator = Mockito.mock(ResultAggregator.class);
        DatabaseService db = Mockito.mock(DatabaseService.class);
        FinanciacionEnricher enricher = Mockito.mock(FinanciacionEnricher.class);
        when(aggregator.financiacionEnricher()).thenReturn(enricher);

        Product stale = producto("https://site.com/a", 1000, SenalFinanciacion.EMPTY);
        AggregatedResult original = new AggregatedResult(
                List.of(stale), java.util.Map.of("Sitio", 1), java.util.Map.of(),
                ResultAggregator.calcularFacets(List.of(stale)), 1000, 1000);

        ScraperService service = new ScraperService(config, aggregator, db);
        service.setLastResultParaTest(original);

        SenalFinanciacion fresh = new SenalFinanciacion("conviene_cuotas", 8.0, 92000, 7666, 12, 40);
        Product enriched = producto("https://site.com/a", 1000, fresh);
        when(enricher.enriquecer(List.of(stale)))
                .thenReturn(List.of(enriched));

        service.recomputarFinanciacion(aggregator);

        AggregatedResult result = service.getLastResult();
        assertThat(result).isNotNull();
        assertThat(result.productos()).hasSize(1);
        assertThat(result.productos().get(0).finan().senal()).isEqualTo("conviene_cuotas");
    }

    @Test
    void recomputeIsNoOpWhenNoCatalogIsLoadedYet() {
        ScraperConfig config = Mockito.mock(ScraperConfig.class);
        ResultAggregator aggregator = Mockito.mock(ResultAggregator.class);
        DatabaseService db = Mockito.mock(DatabaseService.class);

        ScraperService service = new ScraperService(config, aggregator, db);

        service.recomputarFinanciacion(aggregator);

        assertThat(service.getLastResult()).isNull();
        Mockito.verifyNoInteractions(aggregator);
    }

    @Test
    void recomputePreservesFacetsConteoAndErroresUnchanged() {
        ScraperConfig config = Mockito.mock(ScraperConfig.class);
        ResultAggregator aggregator = Mockito.mock(ResultAggregator.class);
        DatabaseService db = Mockito.mock(DatabaseService.class);
        FinanciacionEnricher enricher = Mockito.mock(FinanciacionEnricher.class);
        when(aggregator.financiacionEnricher()).thenReturn(enricher);

        Product stale = producto("https://site.com/b", 5000, SenalFinanciacion.EMPTY);
        var facets = ResultAggregator.calcularFacets(List.of(stale));
        var conteo = java.util.Map.of("Sitio", 1);
        var errores = java.util.Map.of("OtroSitio", "boom");
        AggregatedResult original = new AggregatedResult(
                List.of(stale), conteo, errores, facets, 5000, 5000);

        ScraperService service = new ScraperService(config, aggregator, db);
        service.setLastResultParaTest(original);

        Product enriched = producto("https://site.com/b", 5000,
                new SenalFinanciacion("indistinto", 1.0, 5050, 420, 12, 40));
        when(enricher.enriquecer(List.of(stale)))
                .thenReturn(List.of(enriched));

        service.recomputarFinanciacion(aggregator);

        AggregatedResult result = service.getLastResult();
        assertThat(result.conteoPorSitio()).isEqualTo(conteo);
        assertThat(result.erroresPorSitio()).isEqualTo(errores);
        assertThat(result.facets()).isEqualTo(facets);
        assertThat(result.minPrecio()).isEqualTo(5000);
        assertThat(result.maxPrecio()).isEqualTo(5000);
    }
}
