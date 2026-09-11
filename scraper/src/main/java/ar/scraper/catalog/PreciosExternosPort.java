package ar.scraper.catalog;

import java.util.List;
import java.util.Map;

/**
 * Capacidad de persistencia de {@code precios_externos}: el precio de un
 * producto del catálogo en otros sitios, cacheado por búsqueda externa.
 *
 * <p>Vive en {@code catalog} y no en un área propia porque su payload no
 * trae ningún tipo que lo ubique en otro lado y de lo que habla es del
 * catálogo — el mismo criterio con el que {@code MlOutputPort} quedó acá.</p>
 *
 * <p>La implementa un {@code @Repository} package-private de
 * {@code ar.scraper.db}.</p>
 */
public interface PreciosExternosPort {

    void guardarPreciosExternos(String productoUrl, String sitio,
                                List<Map<String, Object>> resultados);

    List<Map<String, Object>> cargarPreciosExternos(String productoUrl);
}
