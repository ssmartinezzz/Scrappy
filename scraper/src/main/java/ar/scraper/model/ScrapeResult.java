package ar.scraper.model;

import ar.scraper.model.promo.PromosDelSitio;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

public record ScrapeResult(
        String sitio,
        List<Product> productos,
        String error,
        long duracionMs,
        List<SitioCategoriaNodo> arbolDeCategorias,
        PromosDelSitio promosDelSitio
) {
    public ScrapeResult {
        if (promosDelSitio == null) promosDelSitio = PromosDelSitio.NO_LEIDO;
    }

    public ScrapeResult(String sitio, List<Product> productos, String error, long duracionMs,
                        List<SitioCategoriaNodo> arbolDeCategorias) {
        this(sitio, productos, error, duracionMs, arbolDeCategorias, PromosDelSitio.NO_LEIDO);
    }

    public ScrapeResult(String sitio, List<Product> productos, String error, long duracionMs) {
        this(sitio, productos, error, duracionMs, List.of());
    }

    public boolean exitoso() { return StringUtils.isBlank(error); }
}
