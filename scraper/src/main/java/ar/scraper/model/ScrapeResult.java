package ar.scraper.model;

import java.util.List;
import org.apache.commons.lang3.StringUtils;

public record ScrapeResult(
        String sitio,
        List<Product> productos,
        String error,
        long duracionMs,
        List<SitioCategoriaNodo> arbolDeCategorias
) {
    public ScrapeResult(String sitio, List<Product> productos, String error, long duracionMs) {
        this(sitio, productos, error, duracionMs, List.of());
    }

    public boolean exitoso() { return StringUtils.isBlank(error); }
}
