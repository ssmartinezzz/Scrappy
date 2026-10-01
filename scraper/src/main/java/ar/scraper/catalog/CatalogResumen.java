package ar.scraper.catalog;

import java.util.Map;

public record CatalogResumen(double minPrecio, double maxPrecio, Map<String, Long> porSitio,
                             Map<String, Long> rubros, long gymrat, long packs, int total) {
}
