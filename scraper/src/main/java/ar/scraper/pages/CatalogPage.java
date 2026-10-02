package ar.scraper.pages;

import ar.scraper.model.Product;

import java.util.List;

public interface CatalogPage {
    List<Product> scrapeAll();
}
