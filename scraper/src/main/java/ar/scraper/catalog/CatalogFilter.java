package ar.scraper.catalog;

import java.util.List;

/**
 * The 18 filters `/api/data` accepts, as one value object instead of an 18-argument method
 * signature.
 */
public record CatalogFilter(
        List<String> talles,
        String genero,
        List<String> categorias,
        String q,
        String sitio,
        List<String> marcas,
        String badge,
        String segment,
        String rubro,
        Boolean gymrat,
        Boolean pack,
        Double precioMin,
        Double precioMax,
        List<String> subCategorias,
        String fit,
        String estampado,
        String escote,
        String colorDominante
) {

    public static CatalogFilter todo() {
        return new CatalogFilter(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    public CatalogFilter conTalles(List<String> valores) {
        return new CatalogFilter(valores, genero, categorias, q, sitio, marcas, badge, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote, colorDominante);
    }

    public CatalogFilter conMarcas(List<String> valores) {
        return new CatalogFilter(talles, genero, categorias, q, sitio, valores, badge, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote, colorDominante);
    }

    public CatalogFilter conBadge(String valor) {
        return new CatalogFilter(talles, genero, categorias, q, sitio, marcas, valor, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote, colorDominante);
    }

    public CatalogFilter conGenero(String valor) {
        return new CatalogFilter(talles, valor, categorias, q, sitio, marcas, badge, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote, colorDominante);
    }

    public CatalogFilter conCategorias(List<String> valores) {
        return new CatalogFilter(talles, genero, valores, q, sitio, marcas, badge, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote, colorDominante);
    }

    public CatalogFilter conQ(String valor) {
        return new CatalogFilter(talles, genero, categorias, valor, sitio, marcas, badge, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote, colorDominante);
    }

    public CatalogFilter conRangoPrecio(Double min, Double max) {
        return new CatalogFilter(talles, genero, categorias, q, sitio, marcas, badge, segment,
                rubro, gymrat, pack, min, max, subCategorias, fit, estampado, escote, colorDominante);
    }

    public CatalogFilter conPack(Boolean valor) {
        return new CatalogFilter(talles, genero, categorias, q, sitio, marcas, badge, segment,
                rubro, gymrat, valor, precioMin, precioMax, subCategorias, fit, estampado, escote, colorDominante);
    }
}
