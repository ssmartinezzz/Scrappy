package ar.scraper.classification;

import java.util.Map;

/** Where {@link SiteRegistry} reads the {@code sitio} table from. Implemented in {@code ar.scraper.db}. */
public interface SiteSource {

    /** Every site keyed by {@code sitioKey}; throws a runtime exception when the read fails. */
    Map<String, SiteRegistry.Sitio> cargar();
}
