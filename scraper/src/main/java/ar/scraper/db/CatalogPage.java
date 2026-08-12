package ar.scraper.db;

import ar.scraper.model.Product;

import java.util.List;

/**
 * Una página del catálogo más el total que matcheó el filtro (no el tamaño de
 * la página) — lo que `/api/data` necesita para paginar sin traerse todo.
 */
public record CatalogPage(List<Product> productos, int total) {
}
