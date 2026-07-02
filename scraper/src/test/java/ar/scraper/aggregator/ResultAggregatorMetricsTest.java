package ar.scraper.aggregator;

import ar.scraper.db.DatabaseService;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.ml.MlEnricher;
import ar.scraper.ml.PythonRunner;
import ar.scraper.ml.SenalEnricher;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the extraction-metrics validation pass added to
 * {@link ResultAggregator#agregar(List)}.
 *
 * All infrastructure (normalizer, ML pipeline, DB, signal enrichers) is mocked
 * so the test focuses exclusively on the validation / stats logic.
 */
class ResultAggregatorMetricsTest {

    private NormalizerService    normalizer;
    private PythonRunner         pythonRunner;
    private MlEnricher           mlEnricher;
    private SenalEnricher        senalEnricher;
    private FinanciacionEnricher financiacionEnricher;
    private DatabaseService      db;
    private ResultAggregator     aggregator;

    @BeforeEach
    void setUp() {
        normalizer           = mock(NormalizerService.class);
        pythonRunner         = mock(PythonRunner.class);
        mlEnricher           = mock(MlEnricher.class);
        senalEnricher        = mock(SenalEnricher.class);
        financiacionEnricher = mock(FinanciacionEnricher.class);
        db                   = mock(DatabaseService.class);

        // Pass-through mocks — return the input list unchanged
        when(normalizer.normalizar(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(mlEnricher.serializarProductos(anyList())).thenReturn("[]");
        when(pythonRunner.ejecutar(anyString())).thenReturn(null);
        when(mlEnricher.enriquecer(anyList(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(senalEnricher.enriquecer(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(financiacionEnricher.enriquecer(anyList())).thenAnswer(inv -> inv.getArgument(0));
        // Void methods (upsertProductos, guardarMlOutput, etc.) default to no-op on mock

        aggregator = new ResultAggregator(
                normalizer, pythonRunner, mlEnricher, senalEnricher, financiacionEnricher, db);
    }

    /** Convenience: create a minimal Product using the 9-arg legacy constructor. */
    private Product product(String nombre, double precio, String url) {
        return new Product("TestSite", nombre, precio, null, url, "",
                "Remeras", "", List.of());
    }

    // ── Scenario (a): mixed valid/invalid ────────────────────────────────────

    @Test
    void mixedValidAndInvalid_statsCountsAndFilteredListAreCorrect() {
        Product valid    = product("Remera Basica", 1500, "http://test.com/1");
        Product zeroPr   = product("Zapatilla", 0,    "http://test.com/2");   // invalid: precio=0
        Product blankNom = product("", 800,            "http://test.com/3");  // invalid: nombre blank

        ScrapeResult scrapeResult = new ScrapeResult("TestSite",
                List.of(valid, zeroPr, blankNom), null, 100);

        ResultAggregator.AggregatedResult result =
                aggregator.agregar(List.of(scrapeResult));

        ResultAggregator.ExtractionStats stats = result.statsPorSitio().get("TestSite");
        assertThat(stats).isNotNull();
        assertThat(stats.total()).isEqualTo(3);
        assertThat(stats.valid()).isEqualTo(1);
        assertThat(stats.misses()).isEqualTo(2);

        // Invalid products must not appear in the returned producto list
        assertThat(result.productos()).hasSize(1);
        assertThat(result.productos().get(0).url()).isEqualTo("http://test.com/1");
    }

    // ── Scenario (b): all valid ───────────────────────────────────────────────

    @Test
    void allValidProducts_zeroMisses() {
        Product p1 = product("Remera Azul", 1200, "http://test.com/a");
        Product p2 = product("Pantalon",    2500, "http://test.com/b");

        ScrapeResult scrapeResult = new ScrapeResult("TestSite",
                List.of(p1, p2), null, 50);

        ResultAggregator.AggregatedResult result =
                aggregator.agregar(List.of(scrapeResult));

        ResultAggregator.ExtractionStats stats = result.statsPorSitio().get("TestSite");
        assertThat(stats).isNotNull();
        assertThat(stats.total()).isEqualTo(2);
        assertThat(stats.valid()).isEqualTo(2);
        assertThat(stats.misses()).isEqualTo(0);
    }

    // ── Scenario (c): null url ────────────────────────────────────────────────

    @Test
    void nullUrl_isCountedAsMissAndExcludedFromPipeline() {
        Product nullUrl = product("Buzo Nike", 3000, null);

        ScrapeResult scrapeResult = new ScrapeResult("TestSite",
                List.of(nullUrl), null, 30);

        ResultAggregator.AggregatedResult result =
                aggregator.agregar(List.of(scrapeResult));

        ResultAggregator.ExtractionStats stats = result.statsPorSitio().get("TestSite");
        assertThat(stats).isNotNull();
        assertThat(stats.total()).isEqualTo(1);
        assertThat(stats.valid()).isEqualTo(0);
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(result.productos()).isEmpty();
    }

    // ── Approval tests for Work Unit 9 decomposition (written BEFORE the
    //    agregar() named-method split — capture current behavior so the
    //    refactor can be verified as behavior-preserving) ─────────────────────

    // ── deduplicarYOrdenar: dedup by sitio + normalized nombre, keep first ───

    @Test
    void duplicateSiteAndNormalizedNombre_keepsOnlyFirstOccurrence() {
        Product first     = product("Remera Basica", 1500, "http://test.com/1");
        Product duplicate = product("  REMERA BASICA  ", 2000, "http://test.com/2");

        ScrapeResult scrapeResult = new ScrapeResult("TestSite",
                List.of(first, duplicate), null, 100);

        ResultAggregator.AggregatedResult result =
                aggregator.agregar(List.of(scrapeResult));

        assertThat(result.productos()).hasSize(1);
        assertThat(result.productos().get(0).url()).isEqualTo("http://test.com/1");
    }

    @Test
    void multipleValidProducts_sortedByPriceAscending() {
        Product expensive = product("Zapatilla Cara", 5000, "http://test.com/expensive");
        Product cheap     = product("Buzo Barato",    800,  "http://test.com/cheap");

        ScrapeResult scrapeResult = new ScrapeResult("TestSite",
                List.of(expensive, cheap), null, 100);

        ResultAggregator.AggregatedResult result =
                aggregator.agregar(List.of(scrapeResult));

        assertThat(result.productos()).hasSize(2);
        assertThat(result.productos().get(0).url()).isEqualTo("http://test.com/cheap");
        assertThat(result.productos().get(1).url()).isEqualTo("http://test.com/expensive");
    }

    // ── persistirCategoriasRefinadas: snapshot pre-ML categoria (from
    //    `normalizados`), diff against post-ML categoria (from `enriquecidos`) ─

    @Test
    void categoriaChangedByMl_persistedAndCountedInLastCatRefinadas() {
        Product raw = product("Zapatilla Running", 3000, "http://test.com/cat");
        ScrapeResult scrapeResult = new ScrapeResult("TestSite", List.of(raw), null, 10);

        Product normalizado = new Product("TestSite", "Zapatilla Running", 3000, null,
                "http://test.com/cat", "", "Zapatillas", "", List.of());
        when(normalizer.normalizar(anyList())).thenReturn(List.of(normalizado));

        Product enriquecido = new Product("TestSite", "Zapatilla Running", 3000, null,
                "http://test.com/cat", "", "Calzado Deportivo", "", List.of());
        when(mlEnricher.enriquecer(anyList(), any(), any())).thenReturn(List.of(enriquecido));

        aggregator.agregar(List.of(scrapeResult));

        verify(db).actualizarCategoria("http://test.com/cat", "Calzado Deportivo");
        assertThat(aggregator.getLastCatRefinadas()).isEqualTo(1);
    }

    @Test
    void categoriaUnchangedByMl_notPersistedAndNotCounted() {
        Product raw = product("Zapatilla Running", 3000, "http://test.com/cat2");
        ScrapeResult scrapeResult = new ScrapeResult("TestSite", List.of(raw), null, 10);

        Product normalizado = new Product("TestSite", "Zapatilla Running", 3000, null,
                "http://test.com/cat2", "", "Zapatillas", "", List.of());
        when(normalizer.normalizar(anyList())).thenReturn(List.of(normalizado));

        Product enriquecido = new Product("TestSite", "Zapatilla Running", 3000, null,
                "http://test.com/cat2", "", "Zapatillas", "", List.of());
        when(mlEnricher.enriquecer(anyList(), any(), any())).thenReturn(List.of(enriquecido));

        aggregator.agregar(List.of(scrapeResult));

        verify(db, never()).actualizarCategoria(anyString(), anyString());
        assertThat(aggregator.getLastCatRefinadas()).isEqualTo(0);
    }
}
