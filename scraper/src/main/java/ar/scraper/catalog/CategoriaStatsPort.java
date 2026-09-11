package ar.scraper.catalog;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Capability port for the {@code categoria_stats} aggregate: the per
 * category+gender price distribution the ML pipeline computes on every run and
 * every price-relative surface reads back (extract-ml-persistence-ports, port
 * 7 of 13).
 *
 * <p>It lives in {@code catalog} because {@link CategoriaStats}, the type it
 * returns, already does — an area owns its persistence, and {@code
 * areasSonSumideros} forbids the reverse.</p>
 *
 * <p>Method names are the repository's, not the facade's ({@code
 * DatabaseService} keeps its own spelling and renames while delegating). That
 * is decision D3 of extract-catalog-query-port, applied again here so the
 * implementing repository's diff stays {@code implements} plus
 * {@code @Override}.</p>
 */
public interface CategoriaStatsPort {

    /** Persists the {@code categoriaStats} node of an ML pipeline run. */
    void guardarCategoriaStats(JsonNode statsNode);

    /** Reads the stored distributions back, keyed by category+gender. */
    Map<String, CategoriaStats> cargarCategoriaStats();
}
