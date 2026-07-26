package ar.scraper.aggregator;

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
