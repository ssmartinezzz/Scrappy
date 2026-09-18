package ar.scraper.indices;

import java.util.List;

/**
 * Tries each source in order and returns the first usable series. A source
 * that comes back with fewer than {@value #PUNTOS_MINIMOS} points is a
 * failure, not a series — one point cannot support a date-range factor.
 */
public final class FuenteIndiceEncadenada implements FuenteIndicePort {

    private static final int PUNTOS_MINIMOS = 2;

    private final List<FuenteIndicePort> fuentes;

    public FuenteIndiceEncadenada(List<FuenteIndicePort> fuentes) {
        this.fuentes = List.copyOf(fuentes);
    }

    @Override
    public List<PuntoIndice> descargar(Indice indice) throws FuenteIndiceException {
        FuenteIndiceException ultimaFalla = null;
        for (FuenteIndicePort fuente : fuentes) {
            try {
                List<PuntoIndice> puntos = fuente.descargar(indice);
                if (puntos.size() >= PUNTOS_MINIMOS) return puntos;
                ultimaFalla = new FuenteIndiceException(
                        fuente.getClass().getSimpleName() + " devolvió " + puntos.size()
                        + " punto(s), menos de " + PUNTOS_MINIMOS);
            } catch (FuenteIndiceException e) {
                ultimaFalla = e;
            }
        }
        throw ultimaFalla != null
                ? ultimaFalla
                : new FuenteIndiceException("Sin fuentes configuradas para " + indice);
    }
}
