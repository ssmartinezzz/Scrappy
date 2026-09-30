package ar.scraper.outfits;

import ar.scraper.model.Product;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

/**
 * Pure and static, like {@link OutfitRules} and {@link FeedbackModels}, and for the same reason:
 * all three assemblers need it ({@code OutfitService.armar}, the MCKP solver and the greedy
 * fallback), so it belongs to none of them.
 */
final class VisualCoherence {

    private VisualCoherence() {}

    /**
     * Chromatic palette in hue order, wrapping — the colour wheel, as far as the fixed Spanish
     * palette in {@code ml_embeddings.py} resolves it.
     */
    private static final List<String> RUEDA_CROMATICA = List.of(
            "rojo", "naranja", "amarillo", "verde", "celeste", "azul", "violeta", "rosa");

    private static final Set<String> NEUTROS =
            Set.of("negro", "blanco", "gris", "beige", "marron");

    private static final int DISTANCIA_ARMONICA = 2;

    private static final Set<String> FITS_EXTREMOS = Set.of("oversize", "entallado");

    /**
     * A cap or a sneaker can be classified "oversize" by an image model, but that is not a
     * silhouette decision the wearer made, so those slots are exempt from the fit rule (they still
     * take part in the print and colour rules).
     */
    private static boolean fitRelevante(String slot) {
        return slot != null
                && (slot.startsWith(OutfitService.SLOT_TORSO)
                    || OutfitService.SLOT_PIERNAS.equals(slot));
    }

    private static final double PENALIZACION_ESTAMPADO = 0.5;
    private static final double PENALIZACION_FIT       = 0.7;
    private static final double PENALIZACION_COLOR     = 0.7;

    /**
     * Coherence of {@code candidato} against the garments already locked into the outfit, as a
     * multiplier in {@code (0, 1]}..
     */
    static double coherencia(String slot, Product candidato, Map<String, Product> elegidos) {
        if (candidato == null || elegidos == null || elegidos.isEmpty()) return 1.0;

        Product.VisualAttrs v = candidato.visual();
        if (v == null) return 1.0;

        double coherencia = 1.0;
        for (Map.Entry<String, Product> e : elegidos.entrySet()) {
            Product elegido = e.getValue();
            if (elegido == null || elegido == candidato) continue;
            Product.VisualAttrs otro = elegido.visual();
            if (otro == null) continue;

            if (chocaEstampado(v, otro)) coherencia *= PENALIZACION_ESTAMPADO;
            if (fitRelevante(slot) && fitRelevante(e.getKey()) && chocaFit(v, otro)) {
                coherencia *= PENALIZACION_FIT;
            }
            if (chocaColor(v, otro)) coherencia *= PENALIZACION_COLOR;
        }
        return coherencia;
    }

    /** "liso" and "" never clash. */
    private static boolean chocaEstampado(Product.VisualAttrs a, Product.VisualAttrs b) {
        return "estampado".equals(a.estampado()) && "estampado".equals(b.estampado());
    }

    private static boolean chocaFit(Product.VisualAttrs a, Product.VisualAttrs b) {
        return FITS_EXTREMOS.contains(a.fit()) && a.fit().equals(b.fit());
    }

    private static boolean chocaColor(Product.VisualAttrs a, Product.VisualAttrs b) {
        String ca = a.colorDominante();
        String cb = b.colorDominante();
        if (StringUtils.isAnyBlank(ca, cb)) return false;
        if (NEUTROS.contains(ca) || NEUTROS.contains(cb)) return false;

        int ia = RUEDA_CROMATICA.indexOf(ca);
        int ib = RUEDA_CROMATICA.indexOf(cb);
        if (ia < 0 || ib < 0) return false;

        int bruta = Math.abs(ia - ib);
        int distancia = Math.min(bruta, RUEDA_CROMATICA.size() - bruta);
        return distancia > DISTANCIA_ARMONICA;
    }
}
