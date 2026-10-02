package ar.scraper.fuentes;

import ar.scraper.indices.FuenteIndiceException;
import ar.scraper.indices.PuntoIndice;

import java.util.List;

final class FuenteHttp {

    @FunctionalInterface
    interface Parser {
        List<PuntoIndice> parsear(String body) throws FuenteIndiceException;
    }

    private FuenteHttp() {
    }

    static List<PuntoIndice> descargar(Class<?> fuente, String url, Parser parser) throws FuenteIndiceException {
        try {
            return parser.parsear(HttpJson.get(url));
        } catch (FuenteIndiceException e) {
            throw e;
        } catch (Exception e) {
            throw new FuenteIndiceException(fuente.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
