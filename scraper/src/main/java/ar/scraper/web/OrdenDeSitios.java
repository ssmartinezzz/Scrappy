package ar.scraper.web;

import ar.scraper.classification.SiteClassification;
import ar.scraper.config.ScraperConfig.SiteConfig;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Longest-processing-time-first submission order. A site with no history may be the slowest of
 * all, so it goes before every known one.
 */
final class OrdenDeSitios {

    private OrdenDeSitios() {}

    /** {@code duracionesPorClave} is keyed by {@code sitio_key}. Stable: ties keep config order. */
    static List<SiteConfig> masLargosPrimero(List<SiteConfig> sitios,
                                             Map<String, Long> duracionesPorClave) {
        return sitios.stream()
                .sorted(Comparator.comparingLong((SiteConfig s) -> duracionesPorClave
                        .getOrDefault(SiteClassification.sitioKey(s.nombre()), Long.MAX_VALUE))
                        .reversed())
                .toList();
    }
}
