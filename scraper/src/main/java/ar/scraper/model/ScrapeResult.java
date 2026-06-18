package ar.scraper.model;

import java.util.List;

public record ScrapeResult(
        String sitio,
        List<Product> productos,
        String error,
        long duracionMs
) {
    public boolean exitoso() { return error == null || error.isBlank(); }
}
