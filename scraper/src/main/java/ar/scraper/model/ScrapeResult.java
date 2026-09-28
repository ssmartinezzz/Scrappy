package ar.scraper.model;

import java.util.List;
import org.apache.commons.lang3.StringUtils;

public record ScrapeResult(
        String sitio,
        List<Product> productos,
        String error,
        long duracionMs
) {
    public boolean exitoso() { return StringUtils.isBlank(error); }
}
