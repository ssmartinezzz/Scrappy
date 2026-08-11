package ar.scraper.web;

import ar.scraper.model.Product;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Outfit-level coherence rules over {@link Product.VisualAttrs} — the
 * image-derived attributes (fit / estampado / colorDominante) that the ML
 * pipeline has been computing all along and that no assembler read.
 *
 * <p>Pure and static, like {@link OutfitRules} and {@link FeedbackModels}, and for
 * the same reason: all three assemblers need it ({@code OutfitService.armar}, the
 * MCKP solver and the greedy fallback), so it belongs to none of them.</p>
 *
 * <h2>Abstention is the load-bearing property</h2>
 *
 * <p>These attributes come from a zero-shot classifier that declines when it is
 * unsure, so a large share of the catalog carries {@link Product.VisualAttrs#EMPTY}.
 * An empty attribute therefore means "no opinion" and can never trigger a rule. A
 * rule that punished missing data would not be coordinating outfits — it would be
 * quietly demoting every product the classifier skipped.</p>
 *
 * <h2>Weights, not filters</h2>
 *
 * <p>Every rule returns a multiplier in {@code (0, 1]}. The worst possible
 * combination is down-weighted, never excluded — the same stance
 * {@code weightedRandomPick} takes with the ML factor, and what keeps a small
 * catalog from producing an empty slot instead of a slightly loud outfit.</p>
 */
final class VisualCoherence {

    private VisualCoherence() {}

    /**
     * Chromatic palette in hue order, wrapping — the colour wheel, as far as the
     * fixed Spanish palette in {@code ml_embeddings.py} resolves it.
     *
     * <p>This is a hue ORDER, not a table of taste. Encoding the wheel and reading
     * harmony off distance is what keeps the rule reviewable: a hand-written list of
     * "these go together" pairs is a pile of opinions nobody can check, and it grows
     * every time someone disagrees with one entry.</p>
     *
     * <p>The neutrals are deliberately absent — they have no hue to be a distance
     * from, which is exactly why they go with everything.</p>
     */
    private static final List<String> RUEDA_CROMATICA = List.of(
            "rojo", "naranja", "amarillo", "verde", "celeste", "azul", "violeta", "rosa");

    /**
     * Colours with no hue position: they coordinate with every other colour, so they
     * short-circuit the wheel rule. {@code marron} counts as one — an earth tone
     * behaves like a neutral in an outfit even though it is chromatic.
     */
    private static final Set<String> NEUTROS =
            Set.of("negro", "blanco", "gris", "beige", "marron");

    /**
     * Maximum distance on the wheel that still reads as harmonious. Two steps is
     * roughly a quarter turn with this palette's granularity: it keeps analogous
     * families together (azul–celeste, azul–verde, rojo–naranja–amarillo) and
     * separates the true contrasts (rojo–verde, azul–naranja).
     */
    private static final int DISTANCIA_ARMONICA = 2;

    /**
     * Fits that clash with themselves. {@code regular} is absent on purpose: it is
     * the neutral of this axis. And "oversize on top, slim on the bottom" is a
     * deliberate silhouette, not an error — only the SAME extreme twice is penalized.
     */
    private static final Set<String> FITS_EXTREMOS = Set.of("oversize", "entallado");

    /**
     * Slots whose fit means something. A cap or a sneaker can be classified
     * "oversize" by an image model, but that is not a silhouette decision the
     * wearer made, so those slots are exempt from the fit rule (they still take
     * part in the print and colour rules).
     */
    private static boolean fitRelevante(String slot) {
        return slot != null
                && (slot.startsWith(OutfitService.SLOT_TORSO)
                    || OutfitService.SLOT_PIERNAS.equals(slot));
    }

    // Penalties. Two prints is the loudest of the three mistakes, so it costs most.
    // They multiply, so a garment that breaks all three lands near 0.25 — heavily
    // down-weighted, still reachable.
    private static final double PENALIZACION_ESTAMPADO = 0.5;
    private static final double PENALIZACION_FIT       = 0.7;
    private static final double PENALIZACION_COLOR     = 0.7;

    /**
     * Coherence of {@code candidato} against the garments already locked into the
     * outfit, as a multiplier in {@code (0, 1]}.
     *
     * @param slot     sub-slot the candidate would occupy
     * @param candidato the product being considered
     * @param elegidos slot → product already chosen; empty for the first slot, which
     *                 therefore always scores 1.0 (nothing to clash with)
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

    /** Two printed garments in one outfit. "liso" and "" never clash. */
    private static boolean chocaEstampado(Product.VisualAttrs a, Product.VisualAttrs b) {
        return "estampado".equals(a.estampado()) && "estampado".equals(b.estampado());
    }

    /** The same extreme silhouette twice. */
    private static boolean chocaFit(Product.VisualAttrs a, Product.VisualAttrs b) {
        return FITS_EXTREMOS.contains(a.fit()) && a.fit().equals(b.fit());
    }

    /**
     * Two chromatic colours more than {@link #DISTANCIA_ARMONICA} steps apart on the
     * wheel. Abstains on empty values, on names outside the palette, and on anything
     * neutral; identical colours are monochrome, distance 0.
     */
    private static boolean chocaColor(Product.VisualAttrs a, Product.VisualAttrs b) {
        String ca = a.colorDominante();
        String cb = b.colorDominante();
        if (ca == null || cb == null || ca.isBlank() || cb.isBlank()) return false;
        if (NEUTROS.contains(ca) || NEUTROS.contains(cb)) return false;

        int ia = RUEDA_CROMATICA.indexOf(ca);
        int ib = RUEDA_CROMATICA.indexOf(cb);
        if (ia < 0 || ib < 0) return false; // outside the palette — no opinion

        int bruta = Math.abs(ia - ib);
        int distancia = Math.min(bruta, RUEDA_CROMATICA.size() - bruta);
        return distancia > DISTANCIA_ARMONICA;
    }
}
