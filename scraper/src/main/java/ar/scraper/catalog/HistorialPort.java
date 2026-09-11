package ar.scraper.catalog;

import java.util.List;
import java.util.Map;

/**
 * Read-side port for the precio_historico aggregate.
 *
 * <p>Extracted like {@code ar.scraper.favoritos.FavoritosPort} (extract-favoritos-port)
 * so {@code ar.scraper.web} and {@code ar.scraper.ml} depend on this port, not on
 * {@code DatabaseService} directly (extract-preset-historial-ports).</p>
 *
 * <p>Writes are NOT here: they happen inside the product upsert, which belongs
 * to the product aggregate.</p>
 */
public interface HistorialPort {

    /** Raw fecha/precio rows for one product, ascending by fecha. */
    List<Map<String, Object>> cargarHistorial(String url);

    List<HistorialEntry> getHistorialPrecios(String url);

    /**
     * Variante batch: carga el historial de múltiples URLs en una sola consulta,
     * evitando el patrón N+1.
     */
    Map<String, List<HistorialEntry>> getHistorialPrecios(List<String> urls);
}
