package ar.scraper.catalog;

/**
 * Result of merging a scrape batch into the catalog: new rows, updated rows, rows that did not
 * change, and rows soft-deleted (absent from the batch).
 */
public record UpsertStats(int nuevos, int actualizados, int sinCambios, int desactivados) {}
