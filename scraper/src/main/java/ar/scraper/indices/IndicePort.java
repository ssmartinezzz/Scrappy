package ar.scraper.indices;

import java.util.List;

public interface IndicePort {

    /** Upsert by {@code (indice, fecha)}; the caller passes a full history, not a delta. */
    void guardar(List<PuntoIndice> puntos);

    List<PuntoIndice> serie(Indice indice);
}
