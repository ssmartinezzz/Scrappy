package ar.scraper.catalog;

import java.util.List;
import java.util.Map;

/**
 * Writes are NOT here: they happen inside the product upsert, which belongs to the product
 * aggregate.
 */
public interface HistorialPort {

    List<Map<String, Object>> cargarHistorial(String url);

    List<HistorialEntry> getHistorialPrecios(String url);

    /**
     * Variante batch: carga el historial de múltiples URLs en una sola consulta, evitando el patrón
     * N+1.
     */
    Map<String, List<HistorialEntry>> getHistorialPrecios(List<String> urls);
}
