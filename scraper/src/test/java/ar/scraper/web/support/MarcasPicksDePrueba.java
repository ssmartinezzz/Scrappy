package ar.scraper.web.support;

import ar.scraper.web.MarcasPicksController;
import ar.scraper.web.RespuestaGzip;
import ar.scraper.web.ScraperService;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.cache.CatalogoPaginas;
import ar.scraper.web.cache.CuerposGzip;
import tools.jackson.databind.json.JsonMapper;

/** Builds the brands/picks controller by hand: the pages read the very cache the controller gets. */
public final class MarcasPicksDePrueba {

    private MarcasPicksDePrueba() {}

    /** The identity-only negotiator: what a controller built by hand needs when it does not test compression. */
    public static RespuestaGzip sinGzip() {
        return new RespuestaGzip(JsonMapper.builder().build(), new CuerposGzip(), false);
    }

    public static MarcasPicksController controller(ScraperService service, CatalogoDerivadoCache derivados) {
        return new MarcasPicksController(service, derivados, new CatalogoPaginas(derivados), sinGzip());
    }
}
