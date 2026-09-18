package ar.scraper.indices;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * {@code valorEn}/{@code variacionHace} resolve strictly by DATE — the bug this
 * area replaces ({@code SenalCalculator}/{@code SenalEnricher} treating
 * {@code size()-13} as "12 months ago") is a wrong unit test away from
 * recurring, so every scenario here uses irregular point spacing on purpose.
 */
class SerieTest {

    private static PuntoIndice punto(String fecha, double valor) {
        return new PuntoIndice(Indice.IPC, LocalDate.parse(fecha), valor);
    }

    @Test
    void valorEnResuelveAlUltimoPuntoAntesOEnLaFecha() {
        Serie serie = new Serie(Indice.IPC, List.of(
                punto("2026-01-31", 100.0),
                punto("2026-02-28", 103.0),
                punto("2026-03-31", 106.0)));

        assertThat(serie.valorEn(LocalDate.parse("2026-02-15")).orElseThrow().valor())
                .isEqualTo(100.0);
        assertThat(serie.valorEn(LocalDate.parse("2026-03-31")).orElseThrow().valor())
                .isEqualTo(106.0);
    }

    @Test
    void valorEnEsVacioAntesDelPrimerPunto() {
        Serie serie = new Serie(Indice.IPC, List.of(punto("2026-02-28", 103.0)));

        assertThat(serie.valorEn(LocalDate.parse("2026-01-01"))).isEmpty();
    }

    @Test
    void variacionHaceIgnoraCuantosPuntosHayEnElMedio() {
        // Four price-change-shaped points inside the same single month, plus
        // the anchor a month earlier — COUNT says "5 points ago", DATE says "1 month".
        Serie serie = new Serie(Indice.IPC, List.of(
                punto("2026-01-31", 100.0),
                punto("2026-02-01", 100.0),
                punto("2026-02-05", 100.0),
                punto("2026-02-10", 100.0),
                punto("2026-02-28", 110.0)));

        assertThat(serie.variacionHace(1)).contains(10.0);
    }

    @Test
    void deflactorEntreDosPuntosObservadosNoExtrapola() {
        Serie serie = new Serie(Indice.IPC, List.of(
                punto("2026-01-31", 100.0),
                punto("2026-06-30", 120.0)));

        Deflactor d = serie.deflactor(LocalDate.parse("2026-01-31"), LocalDate.parse("2026-06-30"));

        assertThat(d.factor()).isCloseTo(1.2, within(0.0001));
        assertThat(d.confianza()).isEqualTo(Confianza.OBSERVADO);
        assertThat(d.diasExtrapolados()).isZero();
    }

    @Test
    void deflactorMasAllaDelUltimoPuntoExtrapolaYMarca() {
        Serie serie = new Serie(Indice.IPC, List.of(
                punto("2026-01-31", 100.0),
                punto("2026-02-28", 110.0)));

        Deflactor d = serie.deflactor(LocalDate.parse("2026-01-31"), LocalDate.parse("2026-03-30"));

        assertThat(d.confianza()).isEqualTo(Confianza.EXTRAPOLADO);
        assertThat(d.diasExtrapolados()).isEqualTo(30);
        assertThat(d.factor()).isGreaterThan(1.1); // compounded one more month of +10%
    }

    @Test
    void deflactorAntesDelPrimerPuntoClampeaYMarcaExtrapolado() {
        Serie serie = new Serie(Indice.IPC, List.of(
                punto("2026-02-28", 100.0),
                punto("2026-03-31", 110.0)));

        Deflactor d = serie.deflactor(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"));

        assertThat(d.confianza()).isEqualTo(Confianza.EXTRAPOLADO);
        assertThat(d.diasExtrapolados()).isGreaterThan(0);
        assertThat(d.factor()).isCloseTo(1.1, within(0.0001));
    }

    @Test
    void deflactorDeSerieVaciaEsNeutro() {
        Serie serie = Serie.vacia(Indice.IPC);

        Deflactor d = serie.deflactor(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-01"));

        assertThat(d).isEqualTo(Deflactor.NEUTRO);
    }
}
