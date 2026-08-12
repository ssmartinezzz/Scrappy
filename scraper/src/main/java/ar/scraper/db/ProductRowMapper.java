package ar.scraper.db;

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
 */
final class ProductRowMapper {

    /** The column list every read path selects, so a new column lands in all of them at once. */
    static final String COLUMNAS =
            "SELECT url,sitio,nombre,precio,precio_orig,imagen_url," +
            "categoria,genero,ml_score,ml_oferta,ml_tendencia," +
            "ml_segment,ml_zscore,rubro,marca,gymrat,marca_premium,cantidad_unidades,sub_categoria," +
            "fit,estampado,escote,color_dominante FROM productos";

    private ProductRowMapper() {
    }

    static Product map(ResultSet rs, List<String> talles, List<String> badges) throws SQLException {
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
        boolean marcaPremium = rs.getBoolean("marca_premium");
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

        return new Product(
                rs.getString("sitio"), rs.getString("nombre"),
                rs.getDouble("precio"), rs.getString("precio_orig"),
                rs.getString("url"), rs.getString("imagen_url"),
                rs.getString("categoria"), rs.getString("genero"),
                talles, ml, marca != null ? marca : "",
                rubro != null && !rubro.isBlank() ? rubro : "indumentaria",
                gymrat, marcaPremium, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, cantidadUnidades,
                subCategoria != null ? subCategoria : "", visual);
    }
}
