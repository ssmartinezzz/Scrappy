package ar.scraper.db;

import java.util.Map;

/**
 * Contadores globales del catálogo persistido: rango de precios, conteo por
 * sitio y por rubro, gymrat y packs.
 *
 * <p>Antes salían del snapshot de la última corrida, así que no existían hasta
 * que alguien scrapeaba — y por eso `/api/data` devolvía 204 sobre una base con
 * 13543 productos adentro.</p>
 */
public record CatalogResumen(double minPrecio, double maxPrecio, Map<String, Long> porSitio,
                             Map<String, Long> rubros, long gymrat, long packs, int total) {
}
