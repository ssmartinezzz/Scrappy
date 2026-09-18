package ar.scraper.indices;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Projects a {@link Serie} beyond its last observed point. The two indices
 * extrapolate differently on purpose: a currency peg does not drift between
 * two BCRA-set values, an inflation index does — carry-forward is correct for
 * one and wrong for the other.
 */
public final class Extrapolador {

    private static final double DIAS_POR_MES = 30.0;

    private Extrapolador() {
    }

    public static double proyectar(Serie serie, LocalDate fecha) {
        PuntoIndice ultimo = serie.ultimo().orElseThrow();
        if (serie.indice().frecuencia() == Indice.Frecuencia.DIARIO) {
            return ultimo.valor();
        }
        double variacionMensual = serie.variacionHace(1).orElse(0.0) / 100.0;
        double meses = ChronoUnit.DAYS.between(ultimo.fecha(), fecha) / DIAS_POR_MES;
        return ultimo.valor() * Math.pow(1.0 + variacionMensual, meses);
    }
}
