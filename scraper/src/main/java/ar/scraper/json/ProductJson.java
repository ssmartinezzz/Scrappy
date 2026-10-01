package ar.scraper.json;

import ar.scraper.catalog.ProductKey;
import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Like {@link FeedbackModels}, it lives on its own because several bounded contexts share it —
 * {@code /api/recomendados} and {@code /api/favoritos} both write the full row, and {@code safe} is
 * used by nearly every endpoint that builds JSON.
 */
public final class ProductJson {

    private ProductJson() {}

    public static String safe(String s) { return s != null ? s : ""; }

    /**
     * Espeja la fórmula usada en {@code /api/data} (fila del catálogo) para que catálogo, ML y
     * mejores picks compartan una única fuente de verdad. Guard contra división por cero:
     */
    public static double precioUnitario(Product p) {
        return p.cantidadUnidades() > 0 ? p.precio() / p.cantidadUnidades() : p.precio();
    }

    public static void escribir(ObjectNode n, Product p) {
        // Se manda en cada fila para que un link no tenga que ir a la base a buscarlo.
        n.put("key",        ProductKey.of(p.url()));
        n.put("sitio",      safe(p.sitio()));
        n.put("nombre",     safe(p.nombre()));
        n.put("precio",     p.precio());
        n.put("precioOrig", p.precioOriginal());
        n.put("descuento",  p.tieneDescuento());
        String img = safe(p.imagenUrl());
        if (img.startsWith("//")) img = "https:" + img;
        n.put("img",        img);
        n.put("categoria",  safe(p.categoria()));
        n.put("genero",     safe(p.genero()));
        n.put("marca",      safe(p.marca()));
        n.put("rubro",      p.rubro() != null ? p.rubro() : "indumentaria");
        n.put("cantidadUnidades", p.cantidadUnidades());
        n.put("esPack",     p.esPack());
        n.put("precioUnitario", precioUnitario(p));
        ArrayNode tallesArr = n.putArray("talles");
        if (p.talles() != null) p.talles().forEach(tallesArr::add);
        if (p.ml() != null) {
            ObjectNode ml = n.putObject("ml");
            ml.put("badge",      p.ml().badge() != null ? p.ml().badge() : "");
            ArrayNode badgesArr = ml.putArray("badges");
            if (p.ml().badges() != null) p.ml().badges().forEach(badgesArr::add);
            ml.put("scoreP",     p.ml().scoreP());
            ml.put("ofertaReal", p.ml().ofertaReal());
            ml.put("tendencia",  p.ml().tendencia() != null ? p.ml().tendencia() : "estable");
            ml.put("pctil",      p.ml().pctilCategoria());
            ml.put("zScore",     p.ml().zScore());
            ml.put("segment",    p.ml().segment() != null ? p.ml().segment() : "standard");
        }
        Product.SenalCompra senal = p.senal() != null ? p.senal() : Product.SenalCompra.EMPTY;
        ObjectNode senalNode = n.putObject("senal");
        senalNode.put("senal",       senal.senal());
        senalNode.put("scoreCompra", senal.scoreCompra());
        senalNode.put("confianza",   senal.confianzaDeflactor().name().toLowerCase());
    }
}
