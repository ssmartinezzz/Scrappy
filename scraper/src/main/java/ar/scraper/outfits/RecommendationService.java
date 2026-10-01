package ar.scraper.outfits;

import ar.scraper.model.Product;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure, stateless, DB-agnostic — mirrors {@link OutfitService}'s style: no Spring deps, no caching,
 * deterministic per-request full-scan rank over the live in-memory catalog (same cost class as
 * {@code /api/mejores}).
 */
public class RecommendationService {

    /**
     * Hard veto: genero=="infantil" never appears in recomendados, regardless of caller, query
     * params, or relaxation fallback.
     */
    private static final String GENERO_VETADO = "infantil";

    private static final int    BONUS_OFERTA_REAL_FLAG      = 25;
    private static final int    BONUS_BADGE_OFERTA_REAL      = 15;
    private static final int    BONUS_BADGE_PRECIO_HIST_BAJO = 15;
    private static final int    BONUS_BADGE_PRECIO_BAJO      = 10;
    private static final int    BONUS_TENDENCIA_ACTIVA       = 8;

    private static final double BOOST_STEP_PER_LIKE = 0.15;
    private static final int    BOOST_LIKES_CAP      = 5;
    private static final double BOOST_MULTIPLIER_CAP = 1.75;

    /**
     * Empty/null input list returns an empty list (no error) — matches the cold-start scenario's
     * "the call does not error" requirement.
     */
    public List<Product> rank(List<Product> productos, OutfitService.FeedbackModel feedback) {
        if (productos == null) return List.of();
        if (feedback == null) feedback = OutfitService.FeedbackModel.empty();

        Set<String> exclude          = feedback.exclude();
        Set<String> excludeCategoria = feedback.excludeCategoria();
        Map<String, Integer> boostLikeCount = feedback.boostLikeCount();

        return productos.stream()
                // Infantil hard veto — runs FIRST, before pair/categoria exclude.
                .filter(p -> !GENERO_VETADO.equalsIgnoreCase(p.genero() == null ? "" : p.genero().trim()))
                .filter(p -> !exclude.contains(OutfitService.FeedbackModel.keyOf(p)))
                .filter(p -> p.categoria() == null || !excludeCategoria.contains(p.categoria()))
                .sorted(comparator(boostLikeCount))
                .collect(Collectors.toList());
    }

    private Comparator<Product> comparator(Map<String, Integer> boostLikeCount) {
        return Comparator
                .comparingDouble((Product p) -> -finalScore(p, boostLikeCount))
                .thenComparingInt(p -> p.ml() != null ? p.ml().scoreP() : 50)
                .thenComparing(p -> p.url() != null ? p.url() : "");
    }

    private double finalScore(Product p, Map<String, Integer> boostLikeCount) {
        double base = baseMlScore(p);
        double multiplier = boostMultiplier(p, boostLikeCount);
        return base * multiplier;
    }

    public double baseMlScore(Product p) {
        Product.MlScore ml = p.ml() != null ? p.ml() : Product.MlScore.EMPTY;
        double base = 100 - ml.scoreP();

        if (ml.ofertaReal()) base += BONUS_OFERTA_REAL_FLAG;
        List<String> badges = ml.badges() != null ? ml.badges() : List.of();
        if (badges.contains("verified_deal")) base += BONUS_BADGE_OFERTA_REAL;
        if (badges.contains("all_time_low"))  base += BONUS_BADGE_PRECIO_HIST_BAJO;
        if (badges.contains("below_market"))  base += BONUS_BADGE_PRECIO_BAJO;

        String tendencia = ml.tendencia() != null ? ml.tendencia() : "estable";
        if (!tendencia.isBlank() && !"estable".equals(tendencia)) base += BONUS_TENDENCIA_ACTIVA;

        return base;
    }

    private double boostMultiplier(Product p, Map<String, Integer> boostLikeCount) {
        int likes = boostLikeCount.getOrDefault(OutfitService.FeedbackModel.keyOf(p), 0);
        double multiplier = 1.0 + Math.min(likes, BOOST_LIKES_CAP) * BOOST_STEP_PER_LIKE;
        return Math.min(multiplier, BOOST_MULTIPLIER_CAP);
    }
}
