package ar.scraper.catalog;

import java.time.Instant;
import java.util.List;

/**
 * The 20 filters `/api/data` accepts, as one value object instead of an 18-argument method
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
        String colorDominante,
        List<String> excluirCategorias,
        String deporte,
        Instant excluirSinStockDesde
) {

    /** The 20-filter shape that predates the stock filter: no stock filtering. */
    public CatalogFilter(List<String> talles, String genero, List<String> categorias, String q, String sitio,
            List<String> marcas, String badge, String segment, String rubro, Boolean gymrat, Boolean pack,
            Double precioMin, Double precioMax, List<String> subCategorias, String fit, String estampado,
            String escote, String colorDominante, List<String> excluirCategorias, String deporte) {
        this(talles, genero, categorias, q, sitio, marcas, badge, segment, rubro, gymrat, pack, precioMin, precioMax,
                subCategorias, fit, estampado, escote, colorDominante, excluirCategorias, deporte, null);
    }

    public static CatalogFilter todo() {
        return new CatalogFilter(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public CatalogFilter conTalles(List<String> valores) {
        return new CatalogFilter(valores, genero, categorias, q, sitio, marcas, badge, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote, colorDominante, excluirCategorias, deporte, excluirSinStockDesde);
    }

    public CatalogFilter conMarcas(List<String> valores) {
        return new CatalogFilter(talles, genero, categorias, q, sitio, valores, badge, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote, colorDominante, excluirCategorias, deporte, excluirSinStockDesde);
    }

    public CatalogFilter conBadge(String valor) {
        return new CatalogFilter(talles, genero, categorias, q, sitio, marcas, valor, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote, colorDominante, excluirCategorias, deporte, excluirSinStockDesde);
    }

    public CatalogFilter conGenero(String valor) {
        return new CatalogFilter(talles, valor, categorias, q, sitio, marcas, badge, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote, colorDominante, excluirCategorias, deporte, excluirSinStockDesde);
    }

    public CatalogFilter conCategorias(List<String> valores) {
        return new CatalogFilter(talles, genero, valores, q, sitio, marcas, badge, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote, colorDominante, excluirCategorias, deporte, excluirSinStockDesde);
    }

    public CatalogFilter conQ(String valor) {
        return new CatalogFilter(talles, genero, categorias, valor, sitio, marcas, badge, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote, colorDominante, excluirCategorias, deporte, excluirSinStockDesde);
    }

    public CatalogFilter conRangoPrecio(Double min, Double max) {
        return new CatalogFilter(talles, genero, categorias, q, sitio, marcas, badge, segment,
                rubro, gymrat, pack, min, max, subCategorias, fit, estampado, escote, colorDominante, excluirCategorias, deporte, excluirSinStockDesde);
    }

    public CatalogFilter conPack(Boolean valor) {
        return new CatalogFilter(talles, genero, categorias, q, sitio, marcas, badge, segment,
                rubro, gymrat, valor, precioMin, precioMax, subCategorias, fit, estampado, escote, colorDominante, excluirCategorias, deporte, excluirSinStockDesde);
    }

    /** Categories to leave out; rows with no categoria always stay. */
    public CatalogFilter conExcluirCategorias(List<String> valores) {
        return new CatalogFilter(talles, genero, categorias, q, sitio, marcas, badge, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote,
                colorDominante, valores, deporte, excluirSinStockDesde);
    }

    /** Exact sport code; null or blank means no filter. */
    public CatalogFilter conDeporte(String valor) {
        return new CatalogFilter(talles, genero, categorias, q, sitio, marcas, badge, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote,
                colorDominante, excluirCategorias, valor, excluirSinStockDesde);
    }

    /**
     * Leaves out rows confirmed sold out by a stock check taken at or after {@code corte}; unknown rows, and rows
     * whose check is older than {@code corte} (stale, so unknown), stay. Null means no stock filter.
     */
    public CatalogFilter conExcluirSinStock(Instant corte) {
        return new CatalogFilter(talles, genero, categorias, q, sitio, marcas, badge, segment,
                rubro, gymrat, pack, precioMin, precioMax, subCategorias, fit, estampado, escote,
                colorDominante, excluirCategorias, deporte, corte);
    }
}
