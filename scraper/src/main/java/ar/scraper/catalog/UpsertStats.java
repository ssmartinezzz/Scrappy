package ar.scraper.catalog;

/**
 * Result of merging a scrape batch into the catalog: new rows, updated rows,
 * rows that did not change, and rows soft-deleted (absent from the batch).
 *
 * <p>Promoted out of {@code DatabaseService} (extract-catalog-query-port) so
 * {@code ProductPort}, which lives in this area, can return it without
 * {@code ar.scraper.catalog..} depending on {@code ar.scraper.db..}
 * ({@code areasSonSumideros} forbids it).</p>
 */
public record UpsertStats(int nuevos, int actualizados, int sinCambios, int desactivados) {}
