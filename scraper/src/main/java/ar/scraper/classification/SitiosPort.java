package ar.scraper.classification;

import java.util.List;
import java.util.Map;

/**
 * Capability port for the {@code sitio} aggregate: the dynamic site rows the
 * dashboard can add and remove on top of the ones {@code config.properties}
 * declares (extract-ml-persistence-ports, port 10 of 13).
 *
 * <p>It lives in {@code classification} because {@link SiteRegistry} does, and
 * the two are coupled by contract rather than by convenience: every write here
 * ends with a {@code SiteRegistry.reload()}, so the registry's cache of the
 * {@code sitio} table can never go stale behind a write. An implementation
 * that skips that reload is wrong even though it compiles.</p>
 *
 * <p>Note what is NOT here: {@code PLATAFORMAS_VALIDAS}, the platform
 * vocabulary, stays package-private on the implementing repository. {@code
 * PlatformVocabularySyncTest} reaches it through package access to prove it
 * agrees with the SQL CHECK constraint, and widening it to the port would make
 * that a public API rather than a checked invariant.</p>
 */
public interface SitiosPort {

    /** Inserts or updates one dynamic site, then reloads the registry. */
    void guardarSitio(String nombre, String url, String plataforma);

    /** Removes one dynamic site, then reloads the registry. */
    void eliminarSitio(String nombre);

    /** The dynamic sites, as the scraper's config layer consumes them. */
    List<Map<String, String>> cargarSitiosDinamicos();
}
