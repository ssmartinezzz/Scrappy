package ar.scraper.ml;

import ar.scraper.db.DatabaseService.HistorialEntry;
import ar.scraper.model.Product.SenalCompra;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link SenalCalculator}. Replicates the classification
 * logic that previously lived inline in ApiController.recomendacion (lines
 * 588-652), now extracted as a dependency-free, testable function.
 */
class SenalCalculatorTest {

    private HistorialEntry h(String fecha, double precio) {
        return new HistorialEntry(fecha, precio);
    }

    // ── sin_datos ────────────────────────────────────────────────────────

    @Test
    void emptyHistorialReturnsSinDatos() {
        SenalCompra result = SenalCalculator.compute(List.of(), 1.0);

        assertThat(result.senal()).isEqualTo("sin_datos");
        assertThat(result.scoreCompra()).isEqualTo(50);
    }

    @Test
    void nullHistorialReturnsSinDatos() {
        SenalCompra result = SenalCalculator.compute(null, 1.0);

        assertThat(result.senal()).isEqualTo("sin_datos");
        assertThat(result.scoreCompra()).isEqualTo(50);
    }

    @Test
    void singleDataPointDoesNotCrashAndProducesAClassification() {
        // A single point means precioMin == precioMax == precioActual,
        // so rango == 0 and pctDelMin defaults to 50 (mid-range, the
        // "precio_normal" bucket) per the original recomendacion() logic.
        SenalCompra result = SenalCalculator.compute(List.of(h("2026-01-01", 1000.0)), 1.0);

        assertThat(result.senal()).isEqualTo("precio_normal");
        assertThat(result.scoreCompra()).isEqualTo(50);
    }

    // ── comprar_ahora: pctDelMin <= 10.0 ────────────────────────────────

    @Test
    void priceAtHistoricalMinimumIsComprarAhora() {
        // precioActual == precioMin -> pctDelMin == 0 <= 10
        List<HistorialEntry> historial = List.of(
                h("2026-01-01", 1000.0),
                h("2026-02-01", 1200.0),
                h("2026-03-01", 800.0) // current = historical min
        );

        SenalCompra result = SenalCalculator.compute(historial, 1.0);

        assertThat(result.senal()).isEqualTo("comprar_ahora");
        assertThat(result.scoreCompra()).isEqualTo(95);
    }

    // ── muy_buen_momento: cambioReal < -8.0 && tendencia == "bajando" ───

    @Test
    void strongRealDropWithDownwardTrendIsMuyBuenMomento() {
        // Same trend/inflation shape as above, but inject a lower historical
        // floor point so the current price is NOT the min (pctDelMin > 10),
        // letting the muy_buen_momento branch be reached.
        List<HistorialEntry> historial = List.of(
                h("2026-01-01", 1500.0), // historical floor (lower than current)
                h("2026-01-08", 2000.0),
                h("2026-01-15", 2000.0),
                h("2026-01-22", 2000.0), // index 3 = precioAntiguo (size-13=3)
                h("2026-01-29", 2000.0),
                h("2026-02-05", 2000.0),
                h("2026-02-12", 2000.0),
                h("2026-02-19", 2000.0),
                h("2026-02-26", 2000.0),
                h("2026-03-05", 2000.0),
                h("2026-03-12", 2000.0),
                h("2026-03-19", 2000.0), // index 11 = size-4 = p1 for tendencia
                h("2026-03-26", 1900.0),
                h("2026-04-02", 1800.0),
                h("2026-04-09", 1750.0),
                h("2026-04-16", 1700.0)  // index 15 = last = precioActual
        );
        // min=1500, max=2000, rango=500, actual=1700 -> pctDelMin = (1700-1500)/500*100 = 40 > 10
        // tendencia: p1=2000, p2=1700 -> -15% -> "bajando"
        // cambioReal: precioAjustado = 2000*1.0=2000; (1700-2000)/2000*100 = -15 < -8

        SenalCompra result = SenalCalculator.compute(historial, 1.0);

        assertThat(result.senal()).isEqualTo("muy_buen_momento");
        assertThat(result.scoreCompra()).isEqualTo(85);
    }

    // ── buen_momento: cambioReal < -3.0 (and not muy_buen_momento) ──────

    @Test
    void moderateRealDropWithStableTrendIsBuenMomento() {
        // Build a dataset isolating ONLY the buen_momento condition:
        // - pctDelMin strictly between 10 and 80 (not comprar_ahora, not caro)
        // - cambioReal in (-8, -3] (not muy_buen_momento which needs < -8 AND bajando)
        // - tendencia "estable" so esperar/muy_buen_momento guards don't trigger
        // 16 entries, indices 0-15. puntoAntiguo = max(0, 16-13) = 3 -> precioAntiguo = index 3.
        // precioActual = index 15 (chronologically last) = 1930.
        List<HistorialEntry> historial = List.of(
                h("2026-01-01", 1700.0), // index 0 = floor: keeps pctDelMin away from 0
                h("2026-01-08", 2300.0), // index 1 = ceiling: keeps pctDelMin away from 100
                h("2026-01-15", 2000.0),
                h("2026-01-22", 2000.0), // index 3 = precioAntiguo (size-13 = 16-13 = 3)
                h("2026-01-29", 2000.0),
                h("2026-02-05", 2000.0),
                h("2026-02-12", 2000.0),
                h("2026-02-19", 2000.0),
                h("2026-02-26", 2000.0),
                h("2026-03-05", 2000.0),
                h("2026-03-12", 2000.0),
                h("2026-03-19", 2000.0),
                h("2026-03-26", 1940.0), // index 12 = p1 for tendencia (size-4 = 16-4 = 12)
                h("2026-04-02", 1940.0),
                h("2026-04-09", 1940.0),
                h("2026-04-16", 1930.0)  // index 15 = last = precioActual
        );
        // min=1700, max=2300, rango=600, actual=1930 -> pctDelMin=(1930-1700)/600*100=38.3 (10<38.3<80, OK)
        // tendencia: p1(idx12)=1940, p2(idx15)=1930 -> cambioNominal=-0.52% -> "estable" (not bajando/subiendo)
        // cambioReal: precioAntiguo(idx3)=2000, inflacionFactor=1.04 -> precioAjustado=2080
        // cambioReal=(1930-2080)/2080*100=-7.21% -> in (-8,-3] and tendencia!=bajando -> buen_momento

        SenalCompra result = SenalCalculator.compute(historial, 1.04);

        assertThat(result.senal()).isEqualTo("buen_momento");
        assertThat(result.scoreCompra()).isEqualTo(70);
    }

    // ── esperar: cambioReal > 10.0 && tendencia == "subiendo" ───────────

    @Test
    void strongRealIncreaseWithUpwardTrendIsEsperar() {
        // Raise an early historical point above the current price so pctDelMin
        // stays below the "caro" threshold (80) while cambioReal/tendencia still
        // trigger "esperar".
        List<HistorialEntry> isolated = List.of(
                h("2026-01-01", 1500.0), // floor
                h("2026-01-05", 2400.0), // ceiling, set early so pctDelMin < 100 for actual
                h("2026-01-08", 1800.0),
                h("2026-01-15", 1800.0),
                h("2026-01-22", 1800.0), // index 4 -- shifts precioAntiguo index; recompute below
                h("2026-01-29", 1800.0),
                h("2026-02-05", 1800.0),
                h("2026-02-12", 1800.0),
                h("2026-02-19", 1800.0),
                h("2026-02-26", 1800.0),
                h("2026-03-05", 1800.0),
                h("2026-03-12", 1800.0),
                h("2026-03-19", 1900.0),
                h("2026-03-26", 2000.0),
                h("2026-04-02", 2100.0),
                h("2026-04-09", 2150.0),
                h("2026-04-16", 2200.0)  // last = actual = 2200
        );
        // size = 17, puntoAntiguo = size-13 = 4 -> precioAntiguo = index4 = 1800
        // mesesAtras = max(1, size/4) = max(1,4) = 4
        // inflacionFactor = 1.0 -> precioAjustado = 1800
        // cambioReal = (2200-1800)/1800*100 = 22.2% > 10
        // tendencia: p1=index(size-4)=index13=2000, p2=index(size-1)=index16=2200
        //   cambioNominal = (2200-2000)/2000*100=10% > 5 -> "subiendo"
        // pctDelMin: min=1500, max=2400, rango=900, actual=2200 -> pctDelMin=(2200-1500)/900*100=77.8 (<80, OK, not caro)
        SenalCompra result = SenalCalculator.compute(isolated, 1.0);

        assertThat(result.senal()).isEqualTo("esperar");
        assertThat(result.scoreCompra()).isEqualTo(20);
    }

    // ── caro: pctDelMin >= 80.0 ──────────────────────────────────────────

    @Test
    void priceNearHistoricalMaximumIsCaro() {
        List<HistorialEntry> historial = List.of(
                h("2026-01-01", 1000.0),
                h("2026-02-01", 1200.0),
                h("2026-03-01", 1950.0) // current near max (max=1950, min=1000) -> pctDelMin=100
        );

        SenalCompra result = SenalCalculator.compute(historial, 1.0);

        assertThat(result.senal()).isEqualTo("caro");
        assertThat(result.scoreCompra()).isEqualTo(15);
    }

    // ── precio_normal: none of the above conditions match ───────────────

    @Test
    void midRangeStablePriceIsPrecioNormal() {
        // The mid-value point must be chronologically LAST so it becomes
        // "precioActual"; floor/ceiling points are earlier dates.
        List<HistorialEntry> reordered = List.of(
                h("2026-01-01", 1000.0), // floor
                h("2026-02-01", 2000.0), // ceiling
                h("2026-03-01", 1500.0)  // current = mid -> pctDelMin = 50
        );
        // size=3 (<4) -> tendencia stays "estable" (no tendencia block runs)
        // puntoAntiguo = max(0, 3-13) = 0 -> precioAntiguo = index0 = 1000
        // mesesAtras = max(1, 3/4) = 1 -> inflacionFactor=1.0 -> precioAjustado=1000
        // cambioReal = (1500-1000)/1000*100 = 50% -- NOT > 10 && subiendo (tendencia is estable) -> doesn't trigger esperar
        // pctDelMin = (1500-1000)/1000*100 = 50 -- not <=10, not >=80 -> precio_normal
        SenalCompra result = SenalCalculator.compute(reordered, 1.0);

        assertThat(result.senal()).isEqualTo("precio_normal");
        assertThat(result.scoreCompra()).isEqualTo(50);
    }

    // ── Input ordering: caller may pass unsorted historial ──────────────

    @Test
    void unsortedHistorialIsSortedByFechaBeforeClassification() {
        // Same data as priceAtHistoricalMinimumIsComprarAhora but shuffled input order.
        List<HistorialEntry> shuffled = List.of(
                h("2026-03-01", 800.0),  // chronologically last (current)
                h("2026-01-01", 1000.0),
                h("2026-02-01", 1200.0)
        );

        SenalCompra result = SenalCalculator.compute(shuffled, 1.0);

        assertThat(result.senal()).isEqualTo("comprar_ahora");
        assertThat(result.scoreCompra()).isEqualTo(95);
    }
}
