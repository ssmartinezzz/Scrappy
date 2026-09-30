package ar.scraper.classification;

import java.util.Map;

public interface SiteSource {

    /** Every site keyed by {@code sitioKey}; throws a runtime exception when the read fails. */
    Map<String, SiteRegistry.Sitio> cargar();
}
