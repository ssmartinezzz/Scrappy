package ar.scraper.catalog;

import ar.scraper.model.Product;

import java.util.List;

/** One keyset page; {@code siguiente} is null when there is nothing after it. No total: counting is what keyset avoids. */
public record CatalogKeysetPage(List<Product> productos, CatalogCursor siguiente) {
}
