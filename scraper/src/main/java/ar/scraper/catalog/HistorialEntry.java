package ar.scraper.catalog;

/**
 * One point of a product's price history ({@code precio_historico}).
 *
 * <p>Promoted out of {@code DatabaseService} (extract-preset-historial-ports)
 * so {@code ar.scraper.ml} and {@code ar.scraper.web} can depend on this
 * area's own type instead of importing a nested record off the DB facade.</p>
 */
public record HistorialEntry(String fecha, double precio) {}
