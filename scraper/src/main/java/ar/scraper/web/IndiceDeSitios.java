package ar.scraper.web;

import ar.scraper.classification.SiteClassification;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A site's slot in the run's progress list, matched by site key: the run lists {@code vcp}, the
 * scraper reports {@code Vcp}.
 */
final class IndiceDeSitios {

    private final Map<String, Integer> porClave = new HashMap<>();

    IndiceDeSitios(List<String> nombres) {
        for (int i = 0; i < nombres.size(); i++) porClave.put(SiteClassification.sitioKey(nombres.get(i)), i);
    }

    int de(String sitio) {
        return porClave.getOrDefault(SiteClassification.sitioKey(sitio), -1);
    }
}
