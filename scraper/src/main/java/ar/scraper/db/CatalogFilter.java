package ar.scraper.db;

import java.util.List;

/**
 * The 18 filters `/api/data` accepts, as one value object instead of an
 * 18-argument method signature.
 *
 * <p>Introduced by `sql-catalog-filtering`: until now these were applied in
 * Java, by streaming the whole in-memory catalog on every request. The child
 * tables `producto_talle`/`producto_badge` (V7) are what finally make `talle`
 * and `badge` expressible as SQL, which is the reason they exist.</p>
 *
 * <p>Every field keeps the EXACT semantics the in-memory filter had — including
 * the ones that are not obvious, because a filter that quietly changes meaning
 * is worse than a slow one:</p>
 *
 * <ul>
 *   <li>{@code talles}, {@code marcas}, {@code categorias}, {@code subCategorias}:
 *       OR within the list, case-insensitive.</li>
 *   <li>{@code categorias} additionally matches by PREFIX in both directions —
 *       {@code Zapatilla} matches {@code Zapatilla Running} and vice versa.</li>
 *   <li>{@code badge}: set membership, not equality — a product matches if the
 *       badge is anywhere in its badge set, not only if it is the principal one.</li>
 *   <li>{@code segment} and {@code rubro} compare against the DEFAULTED value
 *       ({@code standard} / {@code indumentaria}), so a NULL column still matches
 *       the default the API reports for it.</li>
 *   <li>{@code precioMin}/{@code precioMax} apply to the UNIT price
 *       ({@code precio / cantidadUnidades}), never the pack price.</li>
 *   <li>{@code gymrat} and {@code pack} only filter when TRUE — {@code false}
 *       and {@code null} both mean "do not filter".</li>
 *   <li>{@code q}: case-insensitive substring of the product name.</li>
 * </ul>
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

    /** No filter at all — the whole active catalog. */
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
