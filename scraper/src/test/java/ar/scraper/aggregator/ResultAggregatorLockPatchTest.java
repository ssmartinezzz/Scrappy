package ar.scraper.aggregator;

import ar.scraper.db.ClasificacionBloqueada;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.ml.MlEnricher;
import ar.scraper.ml.PythonRunner;
import ar.scraper.ml.SenalEnricher;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * manual-classification-lock, Phase 5 (task 5.3) — mirrors
 * {@link ResultAggregatorRenormalizarTest}'s mocked-collaborator structure.
 *
 * <p>Proves the in-memory pipeline fix (design problem 3): SQL enforcement
 * alone (V3's {@code sp_upsert_run} guards) is authoritative for
 * persistence, but {@code ScraperService.lastResult} — served directly by
 * {@code GET /api/data}/{@code GET /api/mejores} — is built from THIS run's
 * in-memory list, not re-read from the DB. Without
 * {@code ResultAggregator.aplicarBloqueos}, a locked product's classification
 * would revert in the API response until the next restart even though the
 * database itself was never touched.</p>
 *
 * <p>Applied twice per design: (1) before ML scoring, so a locked product is
 * scored inside the human's category peer group and
 * {@code persistirCategoriasRefinadas}'s diff stays truthful; (2) after
 * stage-1b, because the visual classifier can override {@code categoria}
 * on its own.</p>
 */
@Epic("Aggregation & Grouping")
@Feature("Manual classification lock")
@DisplayName("ResultAggregator — aplicarBloqueos patches the in-memory pipeline")
class ResultAggregatorLockPatchTest {

    private NormalizerService    normalizer;
    private PythonRunner         pythonRunner;
    private MlEnricher           mlEnricher;
    private SenalEnricher        senalEnricher;
    private FinanciacionEnricher financiacionEnricher;
    private DatabaseService      db;
    private ResultAggregator     aggregator;

    @BeforeEach
    void setUp() {
        wireAggregator();
    }

    @Step("Wire ResultAggregator with mocked collaborators")
    private void wireAggregator() {
        normalizer           = mock(NormalizerService.class);
        pythonRunner         = mock(PythonRunner.class);
        mlEnricher           = mock(MlEnricher.class);
        senalEnricher        = mock(SenalEnricher.class);
        financiacionEnricher = mock(FinanciacionEnricher.class);
        db                   = mock(DatabaseService.class);

        when(mlEnricher.serializarProductos(anyList())).thenReturn("[]");
        when(pythonRunner.ejecutar(anyString())).thenReturn(null);
        when(senalEnricher.enriquecer(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(financiacionEnricher.enriquecer(anyList())).thenAnswer(inv -> inv.getArgument(0));

        aggregator = new ResultAggregator(
                normalizer, pythonRunner, mlEnricher, senalEnricher, financiacionEnricher, db);
    }

    private static Product producto(String url, String categoria, String subCategoria,
                                     String marca, String genero, String rubro) {
        return new Product("freres", "Producto " + url, 15000, null, url, "",
                categoria, genero, List.of("M"), Product.MlScore.EMPTY, marca, rubro,
                false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, subCategoria);
    }

    // ─── Pure function: aplicarBloqueos ─────────────────────────────────────

    @Test
    @DisplayName("aplicarBloqueos overrides categoria/subCategoria/marca/genero/rubro for a locked product")
    void aplicarBloqueosOverridesLockedFieldsOnly() {
        Product locked = producto("http://test.com/locked", "Zapatillas", "running", "Nike", "hombre", "indumentaria");
        ClasificacionBloqueada bloqueo =
                new ClasificacionBloqueada("Buzo", "urbano", "Adidas", "mujer", "indumentaria");

        List<Product> patched = ResultAggregator.aplicarBloqueos(
                List.of(locked), Map.of("http://test.com/locked", bloqueo));

        assertThat(patched).hasSize(1);
        Product result = patched.get(0);
        assertThat(result.categoria()).isEqualTo("Buzo");
        assertThat(result.subCategoria()).isEqualTo("urbano");
        assertThat(result.marca()).isEqualTo("Adidas");
        assertThat(result.genero()).isEqualTo("mujer");
        assertThat(result.rubro()).isEqualTo("indumentaria");
        // Untouched fields preserved
        assertThat(result.url()).isEqualTo(locked.url());
        assertThat(result.precio()).isEqualTo(locked.precio());
        assertThat(result.talles()).isEqualTo(locked.talles());
    }

    @Test
    @DisplayName("aplicarBloqueos leaves an unlocked product's classification unchanged")
    void aplicarBloqueosLeavesUnlockedProductUnchanged() {
        Product unlocked = producto("http://test.com/unlocked", "Zapatillas", "running", "Nike", "hombre", "indumentaria");

        List<Product> patched = ResultAggregator.aplicarBloqueos(
                List.of(unlocked), Map.of("http://test.com/other", new ClasificacionBloqueada("Buzo", "urbano", "Adidas", "mujer", "indumentaria")));

        assertThat(patched).hasSize(1);
        assertThat(patched.get(0).categoria()).isEqualTo("Zapatillas");
        assertThat(patched.get(0).marca()).isEqualTo("Nike");
    }

    @Test
    @DisplayName("aplicarBloqueos with a null or empty lock map returns the list unchanged")
    void aplicarBloqueosWithNullOrEmptyMapIsANoOp() {
        Product p = producto("http://test.com/x", "Zapatillas", "running", "Nike", "hombre", "indumentaria");

        assertThat(ResultAggregator.aplicarBloqueos(List.of(p), null)).containsExactly(p);
        assertThat(ResultAggregator.aplicarBloqueos(List.of(p), Map.of())).containsExactly(p);
    }

    // ─── Integration through agregar(): reaches ML input, survives stage-1b ──

    @Test
    @DisplayName("locked classification reaches ML input (before scoring) and lands in the returned AggregatedResult")
    void lockedClassificationReachesMlInputAndTheFinalResult() {
        String url = "http://test.com/locked-e2e";
        Product raw = producto(url, "Zapatillas", "running", "Nike", "hombre", "indumentaria");
        ScrapeResult scrapeResult = new ScrapeResult("freres", List.of(raw), null, 10);

        when(normalizer.normalizar(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(db.cargarClasificacionBloqueada()).thenReturn(
                Map.of(url, new ClasificacionBloqueada("Buzo", "urbano", "Adidas", "mujer", "indumentaria")));

        // Stage-1b is a pass-through here — proves the LOCK (not stage-1b) is
        // what determines the final categoria.
        when(mlEnricher.enriquecer(anyList(), any(), any())).thenAnswer(inv -> inv.getArgument(0));

        ResultAggregator.AggregatedResult result = aggregator.agregar(List.of(scrapeResult));

        // Reached ML input: serializarProductos was called with the LOCKED
        // categoria, not the machine's "Zapatillas" — the peer group ML scores
        // against is truthful.
        ArgumentCaptor<List<Product>> serializados = ArgumentCaptor.forClass(List.class);
        verify(mlEnricher).serializarProductos(serializados.capture());
        assertThat(serializados.getValue()).hasSize(1);
        assertThat(serializados.getValue().get(0).categoria()).isEqualTo("Buzo");

        // Lands in the final result.
        assertThat(result.productos()).hasSize(1);
        Product finalProduct = result.productos().get(0);
        assertThat(finalProduct.categoria()).isEqualTo("Buzo");
        assertThat(finalProduct.subCategoria()).isEqualTo("urbano");
        assertThat(finalProduct.marca()).isEqualTo("Adidas");
        assertThat(finalProduct.genero()).isEqualTo("mujer");

        // Facets reflect the corrected (locked) categoria.
        assertThat(result.facets().categorias()).containsEntry("Buzo", 1L);
        assertThat(result.facets().categorias()).doesNotContainKey("Zapatillas");
    }

    @Test
    @DisplayName("locked classification survives a stage-1b visual override (applied AGAIN after enriquecer)")
    void lockedClassificationSurvivesStage1bOverride() {
        String url = "http://test.com/locked-stage1b";
        Product raw = producto(url, "Zapatillas", "running", "Nike", "hombre", "indumentaria");
        ScrapeResult scrapeResult = new ScrapeResult("freres", List.of(raw), null, 10);

        when(normalizer.normalizar(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(db.cargarClasificacionBloqueada()).thenReturn(
                Map.of(url, new ClasificacionBloqueada("Buzo", "urbano", "Adidas", "mujer", "indumentaria")));

        // Stage-1b demonstrably overrides categoria on its own (visual
        // classifier gate) — simulate it reverting to the machine's guess.
        Product overriddenByStage1b = producto(url, "Pantalones", "trekking", "Puma", "unisex", "tecnologia");
        when(mlEnricher.enriquecer(anyList(), any(), any())).thenReturn(List.of(overriddenByStage1b));

        ResultAggregator.AggregatedResult result = aggregator.agregar(List.of(scrapeResult));

        assertThat(result.productos()).hasSize(1);
        Product finalProduct = result.productos().get(0);
        // The lock wins over stage-1b's override — applied AGAIN after enriquecer.
        assertThat(finalProduct.categoria()).isEqualTo("Buzo");
        assertThat(finalProduct.subCategoria()).isEqualTo("urbano");
        assertThat(finalProduct.marca()).isEqualTo("Adidas");
        assertThat(finalProduct.genero()).isEqualTo("mujer");
    }

    @Test
    @DisplayName("with no locked products, the pipeline is unaffected (backward compatible when db.cargarClasificacionBloqueada is unstubbed)")
    void withNoLockedProductsPipelineIsUnaffected() {
        String url = "http://test.com/no-lock";
        Product raw = producto(url, "Zapatillas", "running", "Nike", "hombre", "indumentaria");
        ScrapeResult scrapeResult = new ScrapeResult("freres", List.of(raw), null, 10);

        when(normalizer.normalizar(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(mlEnricher.enriquecer(anyList(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        // db.cargarClasificacionBloqueada() left UNSTUBBED — Mockito default is null.

        ResultAggregator.AggregatedResult result = aggregator.agregar(List.of(scrapeResult));

        assertThat(result.productos()).hasSize(1);
        assertThat(result.productos().get(0).categoria()).isEqualTo("Zapatillas");
    }
}
