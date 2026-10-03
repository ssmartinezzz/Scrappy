package ar.scraper.catalog;

/**
 * Result of merging a scrape batch into the catalog: new rows, updated rows, rows that did not
 * change, and rows soft-deleted (absent from the batch).
 */
public record UpsertStats(int nuevos, int actualizados, int sinCambios, int desactivados) {

    public static final UpsertStats CERO = new UpsertStats(0, 0, 0, 0);

    public UpsertStats sumar(UpsertStats otra) {
        return new UpsertStats(nuevos + otra.nuevos, actualizados + otra.actualizados,
                sinCambios + otra.sinCambios, desactivados + otra.desactivados);
    }
}
