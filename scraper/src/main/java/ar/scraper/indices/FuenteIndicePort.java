package ar.scraper.indices;

import java.util.List;

/** Outbound port: fetches the full published history of one {@link Indice} from an external source. */
public interface FuenteIndicePort {

    List<PuntoIndice> descargar(Indice indice) throws FuenteIndiceException;
}
