package ar.scraper.catalog;

import java.util.Map;

public record Facets(
        Map<String, Long> talles,
        Map<String, Long> generos,
        Map<String, Long> categorias,
        Map<String, Long> marcas,
        Map<String, Long> badges,
        Map<String, Long> subCategorias,
        Map<String, Long> fits,             // image-derived (fashion-image-classification PR6)
        Map<String, Long> estampados,
        Map<String, Long> escotes,
        Map<String, Long> colorDominantes
) {
    /**
     * Legacy 6-arg constructor — defaults the 4 image-derived visual-attribute
     * facets (T6.5/T6.6) to empty maps for backward compatibility with the
     * ~10 existing test call sites built before PR6.
     */
    public Facets(Map<String, Long> talles, Map<String, Long> generos,
                  Map<String, Long> categorias, Map<String, Long> marcas,
                  Map<String, Long> badges, Map<String, Long> subCategorias) {
        this(talles, generos, categorias, marcas, badges, subCategorias,
             Map.of(), Map.of(), Map.of(), Map.of());
    }
}
