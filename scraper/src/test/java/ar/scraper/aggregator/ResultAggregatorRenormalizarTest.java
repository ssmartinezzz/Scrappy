package ar.scraper.aggregator;

import ar.scraper.db.ClasificacionBloqueada;
import ar.scraper.db.DatabaseService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RED→GREEN coverage for {@link ResultAggregator#renormalizarCatalogo()}'s
 * write-reconciliation fix (agent-chat-finetune WU2): before this fix,
 * {@code categoriaCambiada}/{@code marcaCambiada} were incremented from the
 * intended diff BEFORE the write, and any write exception was silently
 * swallowed — the {@code [RENORM]} log (and API response) reported intended
 * changes as if they had actually been persisted.
 */
@Epic("Aggregation & Grouping")
@Feature("Renormalize catalog")
@DisplayName("ResultAggregator — renormalizarCatalogo write-reconciliation")
class ResultAggregatorRenormalizarTest {

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

    @Step("Wire ResultAggregator with a mocked DatabaseService")
    private void wireAggregator() {
        normalizer           = mock(NormalizerService.class);
        pythonRunner         = mock(PythonRunner.class);
        mlEnricher            = mock(MlEnricher.class);
        senalEnricher         = mock(SenalEnricher.class);
        financiacionEnricher  = mock(FinanciacionEnricher.class);
        db                    = mock(DatabaseService.class);

        aggregator = new ResultAggregator(
                normalizer, pythonRunner, mlEnricher, senalEnricher, financiacionEnricher, db);
    }

    private Product producto(String url, String categoria) {
        return new Product("TestSite", "Producto " + url, 1000, null, url, "",
                categoria, "", List.of());
    }

    @Test
    @DisplayName("escrituras* reflect the REAL row count, not the intended diff — one failure never aborts the batch")
    void reconcilesIntendedVsAppliedAndIsolatesPerProductFailure() {
        // 3 productos con categoría cambiada por la renormalización:
        //   p1 -> UPDATE devuelve 1 fila (escritura realmente aplicada)
        //   p2 -> UPDATE devuelve 0 filas (no existía más / no aplicó)
        //   p3 -> UPDATE lanza una excepción
        Product p1Antes = producto("http://test.com/1", "Zapatillas");
        Product p2Antes = producto("http://test.com/2", "Zapatillas");
        Product p3Antes = producto("http://test.com/3", "Zapatillas");
        Product p1Ahora = producto("http://test.com/1", "Calzado Deportivo");
        Product p2Ahora = producto("http://test.com/2", "Calzado Deportivo");
        Product p3Ahora = producto("http://test.com/3", "Calzado Deportivo");

        when(db.cargarProductos()).thenReturn(List.of(p1Antes, p2Antes, p3Antes));
        when(normalizer.normalizar(anyList())).thenReturn(List.of(p1Ahora, p2Ahora, p3Ahora));

        when(db.actualizarNormalizacion(eq("http://test.com/1"), anyString(), anyString(),
                anyString(), anyList(), anyString())).thenReturn(1);
        when(db.actualizarNormalizacion(eq("http://test.com/2"), anyString(), anyString(),
                anyString(), anyList(), anyString())).thenReturn(0);
        when(db.actualizarNormalizacion(eq("http://test.com/3"), anyString(), anyString(),
                anyString(), anyList(), anyString())).thenThrow(new RuntimeException("boom"));

        Map<String, Integer> resultado = aggregator.renormalizarCatalogo();

        // Batch isolation: los 3 productos fueron revisados pese al fallo de p3.
        assertThat(resultado.get("totalRevisados")).isEqualTo(3);
        assertThat(resultado.get("categoriaCambiada")).isEqualTo(3); // diff intencional, sin cambiar semántica

        assertThat(resultado.get("escriturasIntentadas")).isEqualTo(3);
        assertThat(resultado.get("escriturasAplicadas")).isEqualTo(1);
        assertThat(resultado.get("escriturasFallidas")).isEqualTo(2);

        verify(db).actualizarNormalizacion(eq("http://test.com/1"), anyString(), anyString(),
                anyString(), anyList(), anyString());
        verify(db).actualizarNormalizacion(eq("http://test.com/2"), anyString(), anyString(),
                anyString(), anyList(), anyString());
        verify(db).actualizarNormalizacion(eq("http://test.com/3"), anyString(), anyString(),
                anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("manual-classification-lock: locked URLs are skipped from writes, counted separately, never in escriturasFallidas")
    void lockedUrlsAreSkippedFromWritesAndCountedSeparately() {
        Product p1Antes = producto("http://test.com/1", "Zapatillas");
        Product p4Antes = producto("http://test.com/4", "Zapatillas"); // locked
        Product p1Ahora = producto("http://test.com/1", "Calzado Deportivo");
        Product p4Ahora = producto("http://test.com/4", "Calzado Deportivo"); // title-derived diff, but locked

        when(db.cargarProductos()).thenReturn(List.of(p1Antes, p4Antes));
        when(normalizer.normalizar(anyList())).thenReturn(List.of(p1Ahora, p4Ahora));
        when(db.cargarClasificacionBloqueada()).thenReturn(
                Map.of("http://test.com/4", new ClasificacionBloqueada("Zapatillas", "", "", "", "indumentaria")));

        when(db.actualizarNormalizacion(eq("http://test.com/1"), anyString(), anyString(),
                anyString(), anyList(), anyString())).thenReturn(1);

        Map<String, Integer> resultado = aggregator.renormalizarCatalogo();

        assertThat(resultado.get("totalRevisados")).isEqualTo(2);
        assertThat(resultado.get("categoriaCambiada")).isEqualTo(2); // diff intencional detectado para ambos

        assertThat(resultado.get("escriturasIntentadas")).isEqualTo(1); // solo p1 — p4 nunca se intenta
        assertThat(resultado.get("escriturasAplicadas")).isEqualTo(1);
        assertThat(resultado.get("escriturasFallidas")).isEqualTo(0); // p4 NO cuenta como fallida
        assertThat(resultado.get("escriturasOmitidasPorBloqueo")).isEqualTo(1);

        verify(db).actualizarNormalizacion(eq("http://test.com/1"), anyString(), anyString(),
                anyString(), anyList(), anyString());
        verify(db, never()).actualizarNormalizacion(eq("http://test.com/4"), anyString(), anyString(),
                anyString(), anyList(), anyString());
    }

    @Test
    @DisplayName("review fix F3: a product locked AFTER the entry snapshot (race window) is attributed by a live check, not counted as a failure")
    void productLockedAfterSnapshotIsCountedAsOmittedNotFailed() {
        // p5 is NOT in the entry-time bloqueos snapshot (it got locked mid-run,
        // after the snapshot was taken but before the loop reached its row) —
        // the guarded UPDATE correctly returns 0 rows, but the code must not
        // ASSUME "failure" just because the stale snapshot didn't know about it.
        Product p5Antes = producto("http://test.com/5", "Zapatillas");
        Product p5Ahora = producto("http://test.com/5", "Calzado Deportivo");

        when(db.cargarProductos()).thenReturn(List.of(p5Antes));
        when(normalizer.normalizar(anyList())).thenReturn(List.of(p5Ahora));
        when(db.cargarClasificacionBloqueada()).thenReturn(Map.of()); // empty at snapshot time

        when(db.actualizarNormalizacion(eq("http://test.com/5"), anyString(), anyString(),
                anyString(), anyList(), anyString())).thenReturn(0); // guard fired: now locked
        when(db.estaBloqueado("http://test.com/5")).thenReturn(true); // live read confirms the lock

        Map<String, Integer> resultado = aggregator.renormalizarCatalogo();

        assertThat(resultado.get("escriturasIntentadas")).isEqualTo(1);
        assertThat(resultado.get("escriturasAplicadas")).isEqualTo(0);
        assertThat(resultado.get("escriturasFallidas")).isEqualTo(0); // never — the WARN comment's own invariant
        assertThat(resultado.get("escriturasOmitidasPorBloqueo")).isEqualTo(1);

        verify(db).estaBloqueado("http://test.com/5");
    }

    @Test
    @DisplayName("no changes detected -> zero escrituras*, no write calls")
    void noChangesDetected_zeroEscrituras() {
        Product antes = producto("http://test.com/unchanged", "Zapatillas");
        Product ahora = producto("http://test.com/unchanged", "Zapatillas");

        when(db.cargarProductos()).thenReturn(List.of(antes));
        when(normalizer.normalizar(anyList())).thenReturn(List.of(ahora));

        Map<String, Integer> resultado = aggregator.renormalizarCatalogo();

        assertThat(resultado.get("totalRevisados")).isEqualTo(1);
        assertThat(resultado.get("categoriaCambiada")).isEqualTo(0);
        assertThat(resultado.get("escriturasIntentadas")).isEqualTo(0);
        assertThat(resultado.get("escriturasAplicadas")).isEqualTo(0);
        assertThat(resultado.get("escriturasFallidas")).isEqualTo(0);
        verify(db, never()).actualizarNormalizacion(anyString(), anyString(), anyString(), anyString(), anyList(), anyString());
    }
}
