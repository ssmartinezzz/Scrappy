package ar.scraper.catalog;

import java.util.List;
import java.util.Map;

/**
 * Capacidad de persistencia de {@code precios_externos}: el precio de un producto del catálogo en
 * otros sitios, cacheado por búsqueda externa.
 */
public interface PreciosExternosPort {

    void guardarPreciosExternos(String productoUrl, String sitio,
                                List<Map<String, Object>> resultados);

    List<Map<String, Object>> cargarPreciosExternos(String productoUrl);
}
