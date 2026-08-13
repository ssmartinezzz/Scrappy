package ar.scraper.web;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Serialises a {@link Product} into the JSON row shape the frontend consumes.
 *
 * <p>Extracted verbatim from {@code ApiController} (backlog A3). Like
 * {@link FeedbackModels}, it lives on its own because several bounded contexts
 * share it — {@code /api/recomendados} and {@code /api/favoritos} both write the
 * full row, and {@code safe} is used by nearly every endpoint that builds JSON.</p>
 */
final class ProductJson {

    private ProductJson() {}

    static String safe(String s) { return s != null ? s : ""; }

    /**
     * Precio por unidad — precio de góndola dividido por {@code cantidadUnidades}
     * cuando es un pack. Espeja la fórmula usada en {@code /api/data} (fila del
     * catálogo) para que catálogo, ML y mejores picks compartan una única fuente
     * de verdad. Guard contra división por cero: {@code cantidadUnidades <= 0}
     * cae al precio de estantería.
     *
     * <p>{@code ApiController.precioUnitario} stays as a static delegate to this
     * method: it is part of the surface a test calls directly.</p>
     */
    static double precioUnitario(Product p) {
        return p.cantidadUnidades() > 0 ? p.precio() / p.cantidadUnidades() : p.precio();
    }

    /** Mismo formato que la lista de /api/data, para reuso en DetailPanel. */
    static void escribir(ObjectNode n, Product p) {
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
    }
}
