package ar.scraper.catalog;

import java.time.Instant;
import java.util.Optional;

/**
 * Read-only port for the catalog-search aggregate: paginated search, facets,
 * and the summary strip (`/api/data`, `/api/facets`).
 *
 * <p>Extracted like {@code HistorialPort} (extract-preset-historial-ports) so
 * {@code ar.scraper.web} depends on this port, not on {@code DatabaseService}
 * directly (extract-catalog-query-port).</p>
 *
 * <p>Method names are the REPOSITORY names {@code CatalogQueryRepository}
 * already exposed ({@code buscar}/{@code facetas}/{@code resumen}), not the
 * {@code DatabaseService} facade names ({@code buscarCatalogo}/etc.) — design
 * D3: the sole implementation keeps its names unchanged, so this extraction's
 * repository diff is literally {@code implements} + {@code public @Override},
 * and {@code DatabaseService} is the one place that renames on delegation.</p>
 */
public interface CatalogQueryPort {

    CatalogPage buscar(CatalogFilter filtro, String orden, int page, int size);

    /** @param desde the open run's started_at; empty serves the whole catalogue. */
    CatalogPage buscar(CatalogFilter filtro, String orden, int page, int size,
                       Optional<Instant> desde);

    Facets facetas();

    /** @param desde the open run's started_at; empty counts the whole catalogue. */
    Facets facetas(Optional<Instant> desde);

    CatalogResumen resumen();

    /** @param desde the open run's started_at; empty summarises the whole catalogue. */
    CatalogResumen resumen(Optional<Instant> desde);
}
