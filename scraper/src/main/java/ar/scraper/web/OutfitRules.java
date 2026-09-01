package ar.scraper.web;

import ar.scraper.model.Product;

import java.util.Map;

/**
 * Pure eligibility and mapping rules shared by the outfit assembler and the
 * budget builder.
 *
 * <p>Extracted verbatim from {@link OutfitService} (backlog A3). Like
 * {@link FeedbackModels} and {@link ProductJson}, these live on their own
 * because MORE THAN ONE collaborator needs them: {@code armar} and
 * {@code armarPorCategorias} both filter by genero, both apply the style gate
 * and both build {@code SlotPick}s. All three are pure functions of their
 * arguments, which is why they are static.</p>
 *
 * <h2>The weight policy lives here too</h2>
 *
 * <p>The scalar factors below were originally private to {@code OutfitService},
 * so the budget builder could not reach them and maximized the RAW
 * {@link RecommendationService#baseMlScore} instead. That score is an opportunity
 * signal — {@code 100 - scoreP}, a PRICE percentile, plus four price-derived
 * bonuses — and unbounded it made "cheapest for its category" the entire objective
 * of a surface whose whole point is spending a budget.</p>
 *
 * <p>Every factor here returns 1.0 when it has no signal ({@code CODE-4}) and none
 * of them is a filter: a candidate that loses on all of them is down-weighted, never
 * excluded, which is what keeps a thin catalog from returning an empty slot.</p>
 */
final class OutfitRules {

    private OutfitRules() {}

    /** Half-width of the price band around the target, as a fraction of it. */
    static final double PRICE_BAND_PCT = 0.30;

    /**
     * {@code baseMlScore(MlScore.EMPTY)} — a product the ML pipeline never scored
     * normalizes to exactly 1.0 here, so a catalog without ML keeps its prior weights.
     */
    static final double ML_SCORE_NEUTRO = 50.0;
    static final double ML_FACTOR_MIN   = 0.5;
    static final double ML_FACTOR_MAX   = 2.5;

    /**
     * Like boost. The ceiling (1 + 3*1.0 = 4.0) sits deliberately ABOVE
     * {@link #ML_FACTOR_MAX}: a like is a statement of taste, a badge is an
     * observation about price.
     */
    static final double FEEDBACK_BOOST_STEP = 1.0;
    static final int    FEEDBACK_BOOST_CAP  = 3;

    /**
     * Cost of putting a second garment of the same marca in one outfit. Same
     * magnitude as {@code VisualCoherence}'s fit and colour penalties — it is one
     * more coordination opinion, not a stronger one, and it must stay well above 0
     * so a single-brand catalog can still fill every slot.
     */
    static final double PENALIZACION_MARCA_REPETIDA = 0.7;

    /**
     * ML opportunity as a bounded factor around {@link #ML_SCORE_NEUTRO}.
     * The clamp is the load-bearing part: unbounded, this signal is monotonically
     * decreasing in relative price and drowns out every other term.
     */
    static double mlFactor(double baseMlScore) {
        return Math.clamp(baseMlScore / ML_SCORE_NEUTRO, ML_FACTOR_MIN, ML_FACTOR_MAX);
    }

    /** Like boost for a product's {@code marca|categoria} pair; 1.0 with no likes. */
    static double boostFactor(Product p, Map<String, Integer> boostLikeCount) {
        if (boostLikeCount == null || boostLikeCount.isEmpty()) return 1.0;
        int likes = boostLikeCount.getOrDefault(OutfitService.FeedbackModel.keyOf(p), 0);
        return 1.0 + Math.min(likes, FEEDBACK_BOOST_CAP) * FEEDBACK_BOOST_STEP;
    }

    /**
     * Proximity of a price to {@code centro}, normalized by {@code mitadBanda} so the
     * factor is scale-relative rather than denominated in pesos — the same shape
     * {@code weightedRandomPick} uses, and for the same reason: with raw peso
     * distances a near-exact match in a high-ticket category outweighed everything.
     *
     * @return 1.0 at the centre, falling towards 0 in both directions, never negative
     */
    static double cercaniaDePrecio(double precio, double centro, double mitadBanda) {
        if (!Double.isFinite(centro) || !Double.isFinite(mitadBanda) || mitadBanda <= 0) return 1.0;
        return 1.0 / (1.0 + Math.abs(precio - centro) / mitadBanda);
    }

    /**
     * Brand-repetition factor against the garments already placed.
     *
     * <p>Abstains on a blank marca ({@code CODE-5}): {@code BrandExtractor} leaves it
     * empty when it cannot tell, and two unbranded garments are not evidence of a
     * one-brand outfit. Treating "" as a brand would have made every unbranded pair
     * penalize itself.</p>
     */
    static double diversidadDeMarca(Product candidato, Map<String, Product> elegidos) {
        if (candidato == null || elegidos == null || elegidos.isEmpty()) return 1.0;
        String marca = candidato.marca() != null ? candidato.marca().trim() : "";
        if (marca.isEmpty()) return 1.0;

        double factor = 1.0;
        for (Product elegido : elegidos.values()) {
            if (elegido == null || elegido == candidato) continue;
            String otra = elegido.marca() != null ? elegido.marca().trim() : "";
            if (!otra.isEmpty() && otra.equalsIgnoreCase(marca)) {
                factor *= PENALIZACION_MARCA_REPETIDA;
            }
        }
        return factor;
    }

    static boolean generoElegible(Product p, String generoSolicitado) {
        String g = p.genero() != null ? p.genero().trim() : "";
        if ("infantil".equalsIgnoreCase(g)) return false; // nunca en el armador, ni pidiendo unisex
        if (g.isEmpty()) return true;
        if ("unisex".equalsIgnoreCase(g)) return true;
        if (generoSolicitado == null || generoSolicitado.isBlank()) return true; // sin genero pedido: todo elegible
        if ("unisex".equalsIgnoreCase(generoSolicitado)) return true; // pedido unisex: todo elegible
        return g.equalsIgnoreCase(generoSolicitado);
    }

    static boolean pasaEstiloGate(Product p, String slot, String estilo) {
        boolean esRopaGateada = slot.startsWith(OutfitService.SLOT_TORSO)
                || OutfitService.SLOT_PIERNAS.equals(slot);
        if (!esRopaGateada) return true;
        if ("casual".equalsIgnoreCase(estilo)) return !p.gymrat();
        return p.gymrat(); // default / "gym"
    }

    static OutfitService.SlotPick toSlotPick(String slot, Product p) {
        String img = p.imagenUrl() != null ? p.imagenUrl() : "";
        if (img.startsWith("//")) img = "https:" + img;
        return new OutfitService.SlotPick(
                slot,
                p.sitio() != null ? p.sitio() : "",
                p.nombre() != null ? p.nombre() : "",
                p.precio(),
                p.url() != null ? p.url() : "",
                img,
                p.categoria() != null ? p.categoria() : "",
                p.marca() != null ? p.marca() : "");
    }
}
