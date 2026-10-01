package ar.scraper.catalog;

/**
 * {@code n} is a raw count (never rounded), the other 11 are the pipeline's own {@code round()}'d
 * values — this record does not re-round anything, it carries what Python already decided.
 */
public record CategoriaStats(
        int n,
        long mean,
        long median,
        long mode,
        long std,
        double cv,
        long q1,
        long q3,
        long iqr,
        long mad,
        long fenceLow,
        long fenceHigh
) {
}
