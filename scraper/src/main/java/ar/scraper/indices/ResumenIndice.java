package ar.scraper.indices;

import java.time.LocalDate;
import java.util.List;

/** Snapshot of one {@link Indice} for {@code GET /api/indices}. */
public record ResumenIndice(
        Indice indice,
        double ultimoValor,
        LocalDate ultimaFecha,
        Double variacionMensual,
        Double variacionInteranual,
        Double variacion3m,
        Confianza confianza,
        List<PuntoIndice> ultimos
) {
    public static ResumenIndice sinDatos(Indice indice) {
        return new ResumenIndice(indice, 0.0, null, null, null, null, Confianza.SIN_DATOS, List.of());
    }
}
