package ar.scraper.catalog;

import java.util.Map;

public record Facets(
        Map<String, Long> talles,
        Map<String, Long> generos,
        Map<String, Long> categorias,
        Map<String, Long> marcas,
        Map<String, Long> badges,
        Map<String, Long> subCategorias,
        Map<String, Long> fits,
        Map<String, Long> estampados,
        Map<String, Long> escotes,
        Map<String, Long> colorDominantes
) {
    public Facets(Map<String, Long> talles, Map<String, Long> generos,
                  Map<String, Long> categorias, Map<String, Long> marcas,
                  Map<String, Long> badges, Map<String, Long> subCategorias) {
        this(talles, generos, categorias, marcas, badges, subCategorias,
             Map.of(), Map.of(), Map.of(), Map.of());
    }
}
