package ar.scraper.db;

import ar.scraper.classification.SiteClassification;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.model.Product;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * {@code marcaPremium} is resolved from {@link SiteRegistry#esPremium}, keyed by
 * {@link SiteClassification#sitioKey} of the row's own {@code sitio} value — a {@code HashMap}
 * lookup against a ~30-row in-memory map at the point the row is already being mapped, not a SQL
 * {@code LEFT JOIN} (measured at +28% on {@code cargarProductos()}, over the pre-committed 5%
 * threshold) and not a stored column anymore.
 */
final class ProductRowMapper {

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

        Double precioOrig = rs.getObject("precio_orig", Double.class);

        return Product.builder()
                .sitio(sitio)
                .nombre(rs.getString("nombre"))
                .precio(rs.getDouble("precio"))
                .precioOriginal(precioOrig)
                .url(rs.getString("url"))
                .imagenUrl(rs.getString("imagen_url"))
                .categoria(rs.getString("categoria"))
                .genero(rs.getString("genero"))
                .talles(talles)
                .ml(ml)
                .marca(marca != null ? marca : "")
                .rubro(StringUtils.isNotBlank(rubro) ? rubro : "indumentaria")
                .gymrat(gymrat)
                .marcaPremium(marcaPremium)
                .senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(cantidadUnidades)
                .subCategoria(subCategoria != null ? subCategoria : "")
                .visual(visual)
                .build();
    }
}
