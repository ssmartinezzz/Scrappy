package ar.scraper.indices;

import java.util.List;

public interface FuenteIndicePort {

    List<PuntoIndice> descargar(Indice indice) throws FuenteIndiceException;
}
