package ar.scraper.financiacion;

/**
 * A financing preset ("¿conviene en cuotas?"): a recargo/cuotas pair the user
 * configures and can mark {@code activo}.
 *
 * <p>Promoted out of {@code DatabaseService} (extract-preset-historial-ports,
 * mirroring {@code ar.scraper.favoritos.FavoritosPort}'s shape) so {@code
 * ar.scraper.ml} and {@code ar.scraper.web} can depend on this area's own
 * types instead of importing a nested record off the DB facade.</p>
 */
public record Preset(int id, String label, double recargoPct, int cuotas, boolean activo) {}
