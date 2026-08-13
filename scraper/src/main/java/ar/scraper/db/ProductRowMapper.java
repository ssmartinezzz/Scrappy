package ar.scraper.db;

import ar.scraper.aggregator.normalize.SiteClassification;
import ar.scraper.aggregator.normalize.SiteRegistry;
import ar.scraper.model.Product;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * The single row → {@link Product} mapping, shared by every read path
 * (`sql-catalog-filtering`).
 *
 * <p>It was private to {@link ProductRepository} until `/api/data` started
 * querying the catalog with SQL instead of filtering the in-memory snapshot.
 * Copying it into the new query path is exactly the divergence V7's design
 * refused to accept between {@code cargarProductos()} and
 * {@code obtenerProducto()} — one mapper, or the two paths eventually disagree
 * about what a product is.</p>
 *
 * <p>{@code talles} and {@code badges} are NOT read from the row: they live in
 * their own tables since V7 and are passed in already ordered by {@code posicion}.
 * {@code senal}/{@code finan} are always EMPTY here — they are computed, never
 * stored, and whoever needs them enriches afterwards.</p>
 *
 * <p>close-1nf-and-3nf-foundation extension (design E2, coordinator-directed
 * third option): {@code marcaPremium} is resolved from
 * {@link SiteRegistry#esPremium}, keyed by {@link SiteClassification#sitioKey}
 * of the row's own {@code sitio} value — a {@code HashMap} lookup against a
 * ~30-row in-memory map at the point the row is already being mapped, not a
 * SQL {@code LEFT JOIN} (measured at +28% on {@code cargarProductos()}, over
 * the pre-committed 5% threshold) and not a stored column anymore.
 * {@code sitioKey()} is the SAME normalization {@code RubroResolver}/
 * {@code ScraperFactory}/{@code NormalizerService} already funnel through
 * {@code SiteRegistry} — the stored {@code sitio} display value and its
 * normalized key are different strings on purpose (V18's two-column design),
 * and this reuses the one function that bridges them instead of a fourth
 * copy.</p>
 */
final class ProductRowMapper {

    /** The column list every read path selects, so a new column lands in all of them at once. */
    static final String COLUMNAS =
            "SELECT url,sitio,nombre,precio,precio_orig,imagen_url," +
            "categoria,genero,ml_score,ml_oferta,ml_tendencia," +
            "ml_segment,ml_zscore,rubro,marca,gymrat,cantidad_unidades,sub_categoria," +
            "fit,estampado,escote,color_dominante FROM productos";

    private ProductRowMapper() {
    }

    static Product map(ResultSet rs, List<String> talles, List<String> badges,
                        SiteRegistry siteRegistry) throws SQLException {
        Product.MlScore ml = new Product.MlScore(
                rs.getInt("ml_score"),
                badges,
                rs.getBoolean("ml_oferta"),
                rs.getString("ml_tendencia") != null ? rs.getString("ml_tendencia") : "",
                rs.getInt("ml_score"),
                rs.getDouble("ml_zscore"),
                rs.getString("ml_segment") != null ? rs.getString("ml_segment") : "standard"
        );

        String marca = rs.getString("marca");
        String rubro = rs.getString("rubro");
        boolean gymrat = rs.getBoolean("gymrat");
        String sitio = rs.getString("sitio");
        boolean marcaPremium = siteRegistry.esPremium(SiteClassification.sitioKey(sitio));
        int cantidadUnidades = rs.getInt("cantidad_unidades");
        if (cantidadUnidades < 1) cantidadUnidades = 1;
        String subCategoria = rs.getString("sub_categoria");

        String fit = rs.getString("fit");
        String estampado = rs.getString("estampado");
        String escote = rs.getString("escote");
        String colorDominante = rs.getString("color_dominante");
        Product.VisualAttrs visual = new Product.VisualAttrs(
                fit != null ? fit : "",
                estampado != null ? estampado : "",
                escote != null ? escote : "",
                colorDominante != null ? colorDominante : "");

        // precio_orig es double precision desde V17 (DD7) — lectura numérica
        // directa, sin reparseo. getObject(..., Double.class) devuelve null
        // limpio para SQL NULL en vez de necesitar un wasNull() aparte.
        Double precioOrig = rs.getObject("precio_orig", Double.class);

        return new Product(
                sitio, rs.getString("nombre"),
                rs.getDouble("precio"), precioOrig,
                rs.getString("url"), rs.getString("imagen_url"),
                rs.getString("categoria"), rs.getString("genero"),
                talles, ml, marca != null ? marca : "",
                rubro != null && !rubro.isBlank() ? rubro : "indumentaria",
                gymrat, marcaPremium, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, cantidadUnidades,
                subCategoria != null ? subCategoria : "", visual);
    }
}
