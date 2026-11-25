package ar.scraper.catalog;

import java.util.List;

/**
 * One product's re-derived classification. {@code talles == null} leaves the stored sizes alone, an
 * empty list clears them; a blank {@code rubro} keeps the stored one.
 */
public record Normalizacion(String url, String categoria, String marca, String genero,
                            List<String> talles, String subCategoria, String rubro) {}
