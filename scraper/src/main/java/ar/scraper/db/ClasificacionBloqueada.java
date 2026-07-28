package ar.scraper.db;

/**
 * Read-side value object for the manual classification lock (design D3/D4,
 * "Data Flow" and "Interfaces"). One instance per locked product, produced
 * by {@link DatabaseService#cargarClasificacionBloqueada()} and applied by
 * {@code ar.scraper.aggregator.ResultAggregator#aplicarBloqueos} to the
 * in-memory pipeline — SQL enforcement (V3's {@code sp_upsert_run} guards)
 * is authoritative for persistence, but the in-memory {@code lastResult}
 * snapshot served by {@code GET /api/data}/{@code GET /api/mejores} needs
 * this too, or it would keep showing a reverted classification until the
 * next restart.
 */
public record ClasificacionBloqueada(
        String categoria,
        String subCategoria,
        String marca,
        String genero,
        String rubro
) {
}
