package ar.scraper.indices;

import java.util.List;

/** Outbound port: persistence for {@code indice_valor}. */
public interface IndicePort {

    /** Upsert by {@code (indice, fecha)}; the caller passes a full history, not a delta. */
    void guardar(List<PuntoIndice> puntos);

    /** Ascending by fecha; empty when the index has no stored points yet. */
    List<PuntoIndice> serie(Indice indice);
}
