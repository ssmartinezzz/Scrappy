package ar.scraper.ml;

import ar.scraper.model.Product.SenalFinanciacion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure unit tests for {@link FinanciacionCalculator}. Mirrors
 * {@code SenalCalculatorTest}'s shape: dependency-free, deterministic inputs,
 * one scenario per classification branch plus fallback/guard cases.
 */
class FinanciacionCalculatorTest {

    // ── Present-Value calculation ────────────────────────────────────────

    @Test
    void standardVpIsBelowNominalCuotasTotal() {
        // precioContado=100000, recargo=40%, n=12, i=0.035
        // precioCuotas = 140000, cuota = 11666.666...
        SenalFinanciacion result = FinanciacionCalculator.compute(100000, 40, 12, 0.035);

        assertThat(result.cuota()).isCloseTo(11666.6667, within(0.01));
        assertThat(result.vp()).isLessThan(140000.0);
        assertThat(result.cuotas()).isEqualTo(12);
        assertThat(result.recargoPct()).isEqualTo(40);
    }

    @Test
    void zeroMonthlyInflationDoesNotThrowAndVpEqualsNominal() {
        // i = 0 -> each term is cuota/(1+0)^k = cuota -> VP = cuota * n = nominal total
        SenalFinanciacion result = FinanciacionCalculator.compute(100000, 40, 12, 0.0);

        assertThat(result.vp()).isCloseTo(140000.0, within(0.01));
    }

    @Test
    void negativeMonthlyInflationDoesNotThrowAndProducesVpAboveNominal() {
        // i < 0 (deflation): (1+i)^k < 1 -> dividing by it INCREASES each term -> VP > nominal
        SenalFinanciacion result = FinanciacionCalculator.compute(100000, 40, 12, -0.02);

        assertThat(result.vp()).isGreaterThan(140000.0);
    }

    // ── Three-way classification (±5.0%) ─────────────────────────────────

    @Test
    void highSavingsClassifiesAsConvieneCuotas() {
        // Large recargo with low/no inflation discount keeps ahorroReal in range,
        // but to isolate classification we drive ahorroReal directly via a
        // scenario with negative recargo (a "discount" for paying in installments)
        // and zero inflation so VP == nominal < contado, producing ahorroReal > 5.
        SenalFinanciacion result = FinanciacionCalculator.compute(100000, -10, 12, 0.0);

        // precioCuotas = 90000, VP = 90000 (i=0), ahorroReal = (100000-90000)/100000*100 = 10.0 > 5.0
        assertThat(result.ahorroReal()).isCloseTo(10.0, within(0.01));
        assertThat(result.senal()).isEqualTo("conviene_cuotas");
    }

    @Test
    void lowSavingsClassifiesAsConvieneContado() {
        // High recargo + high inflation discount applied only nominally (i=0 so
        // VP==nominal precioCuotas) drives VP well above contado -> ahorroReal very negative.
        SenalFinanciacion result = FinanciacionCalculator.compute(100000, 40, 12, 0.0);

        // VP = 140000 (i=0) -> ahorroReal = (100000-140000)/100000*100 = -40.0 < -5.0
        assertThat(result.ahorroReal()).isCloseTo(-40.0, within(0.01));
        assertThat(result.senal()).isEqualTo("conviene_contado");
    }

    @Test
    void midRangeSavingsClassifiesAsIndistinto() {
        // recargo=18%, i=0.035, n=12 -> ahorroReal ~= 4.977%, inside (-5.0, 5.0).
        SenalFinanciacion result = FinanciacionCalculator.compute(100000, 18, 12, 0.035);

        assertThat(result.ahorroReal()).isBetween(-5.0, 5.0);
        assertThat(result.senal()).isEqualTo("indistinto");
    }

    @Test
    void ahorroRealExactlyAtPositiveThresholdIsIndistinto() {
        // Boundary: ahorroReal == 5.0 exactly must NOT be conviene_cuotas (condition is strictly >).
        // precioContado=100000, recargo=-5 (precioCuotas=95000), i=0 -> VP=95000 -> ahorroReal=5.0 exactly.
        SenalFinanciacion result = FinanciacionCalculator.compute(100000, -5, 12, 0.0);

        assertThat(result.ahorroReal()).isCloseTo(5.0, within(0.0001));
        assertThat(result.senal()).isEqualTo("indistinto");
    }

    @Test
    void ahorroRealExactlyAtNegativeThresholdIsIndistinto() {
        // Boundary: ahorroReal == -5.0 exactly must NOT be conviene_contado (condition is strictly <).
        SenalFinanciacion result = FinanciacionCalculator.compute(100000, 5, 12, 0.0);

        assertThat(result.ahorroReal()).isCloseTo(-5.0, within(0.0001));
        assertThat(result.senal()).isEqualTo("indistinto");
    }

    // ── Fallback / guard states ──────────────────────────────────────────

    @Test
    void zeroPrecioContadoReturnsEmpty() {
        SenalFinanciacion result = FinanciacionCalculator.compute(0, 40, 12, 0.035);

        assertThat(result).isEqualTo(SenalFinanciacion.EMPTY);
        assertThat(result.senal()).isEqualTo("sin_datos");
    }

    @Test
    void negativePrecioContadoReturnsEmpty() {
        SenalFinanciacion result = FinanciacionCalculator.compute(-100, 40, 12, 0.035);

        assertThat(result).isEqualTo(SenalFinanciacion.EMPTY);
    }

    @Test
    void zeroCuotasReturnsEmptyWithoutDivisionByZero() {
        SenalFinanciacion result = FinanciacionCalculator.compute(100000, 40, 0, 0.035);

        assertThat(result).isEqualTo(SenalFinanciacion.EMPTY);
    }

    @Test
    void negativeCuotasReturnsEmpty() {
        SenalFinanciacion result = FinanciacionCalculator.compute(100000, 40, -1, 0.035);

        assertThat(result).isEqualTo(SenalFinanciacion.EMPTY);
    }
}
