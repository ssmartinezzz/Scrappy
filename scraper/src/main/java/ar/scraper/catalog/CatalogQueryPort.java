package ar.scraper.catalog;

import java.time.Instant;
import java.util.Optional;

/**
 * Read-only port for the catalog-search aggregate: paginated search, facets, and the summary strip
 * (`/api/data`, `/api/facets`).
 */
public interface CatalogQueryPort {

    CatalogPage buscar(CatalogFilter filtro, String orden, int page, int size);

    CatalogPage buscar(CatalogFilter filtro, String orden, int page, int size,
                       Optional<Instant> desde);

    /**
     * Keyset page for {@code precio_asc} / {@code precio_desc}: the {@code size} rows strictly after {@code cursor}
     * (null = from the start), by {@code (precio, url)}, with no OFFSET and no COUNT.
     *
     * @throws IllegalArgumentException for any other {@code orden}
     */
    CatalogKeysetPage buscarPorCursor(CatalogFilter filtro, String orden, CatalogCursor cursor, int size,
                                      Optional<Instant> desde);

    Facets facetas();

    Facets facetas(Optional<Instant> desde);

    CatalogResumen resumen();

    CatalogResumen resumen(Optional<Instant> desde);
}
