package ar.scraper.web;

import ar.scraper.model.Product;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Ranking core for the "Para ti" personalized recommendations feed
 * (design.md Decision 3, personalized-recommendations-feed). Pure, stateless,
 * DB-agnostic — mirrors {@link OutfitService}'s style: no Spring deps beyond
 * {@code @Service}, no caching, deterministic per-request full-scan rank
 * over the live in-memory catalog (same cost class as {@code /api/mejores}).
 *
 * Algorithm (exact order, per design.md):
 *   1. Veto (hard exclude) — drop products whose marca|categoria pair is in
 *      {@code feedback.exclude()} OR whose bare categoria is in
 *      {@code feedback.excludeCategoria()}. Mirrors OutfitService.armar()'s
 *      exclude semantics, reusing the SAME FeedbackModel record (no new type).
 *   2. Base ML score — {@code base = (100 - scoreP)} (scoreP lower = better
 *      opportunity), plus additive badge bonuses.
 *   3. Taste-boost multiplier — {@code 1.0 + min(likes,5)*0.15}, capped at
 *      1.75x. Cold start (no feedback) -> multiplier always 1.0 -> pure ML order.
 *   4. Deterministic sort — descending by final score, tiebreak ascending by
 *      scoreP, then by url for full stability.
 */
@Service
public class RecommendationService {

    /**
     * Hard veto: genero=="infantil" never appears in recomendados, regardless
     * of caller, query params, or relaxation fallback. Mirrors
     * {@code OutfitService.CALZADO_VETADO}'s static-veto style (L101) and
     * {@code OutfitService.generoElegible()}'s infantil exclusion (L327) —
     * scoped here to the recomendados ranking core so the veto applies even
     * if a future caller forgets the pre-filter.
     */
    private static final String GENERO_VETADO = "infantil";

    private static final int    BONUS_OFERTA_REAL_FLAG      = 25;
    private static final int    BONUS_BADGE_OFERTA_REAL      = 15;
    private static final int    BONUS_BADGE_PRECIO_HIST_BAJO = 15;
    private static final int    BONUS_BADGE_PRECIO_BAJO      = 10;
    private static final int    BONUS_TENDENCIA_ACTIVA       = 8;

    private static final double BOOST_STEP_PER_LIKE = 0.15;
    private static final int    BOOST_LIKES_CAP      = 5;
    private static final double BOOST_MULTIPLIER_CAP = 1.75; // 1.0 + 5*0.15

    /**
     * Ranks the given products against the supplied feedback model. Empty/null
     * input list returns an empty list (no error) — matches the cold-start
     * scenario's "the call does not error" requirement.
     */
    public List<Product> rank(List<Product> productos, OutfitService.FeedbackModel feedback) {
        if (productos == null) return List.of();
        if (feedback == null) feedback = OutfitService.FeedbackModel.empty();

        Set<String> exclude          = feedback.exclude();
        Set<String> excludeCategoria = feedback.excludeCategoria();
        Map<String, Integer> boostLikeCount = feedback.boostLikeCount();

        return productos.stream()
                // 0. Infantil hard veto — runs FIRST, before pair/categoria exclude.
                .filter(p -> !GENERO_VETADO.equalsIgnoreCase(p.genero() == null ? "" : p.genero().trim()))
                // 1. Veto — pair-exclude OR category-wide exclude, regardless of marca.
                .filter(p -> !exclude.contains(OutfitService.FeedbackModel.keyOf(p)))
                .filter(p -> p.categoria() == null || !excludeCategoria.contains(p.categoria()))
                .sorted(comparator(boostLikeCount))
                .collect(Collectors.toList());
    }

    private Comparator<Product> comparator(Map<String, Integer> boostLikeCount) {
        return Comparator
                .comparingDouble((Product p) -> -finalScore(p, boostLikeCount)) // descending by final
                .thenComparingInt(p -> p.ml() != null ? p.ml().scoreP() : 50)   // ascending scoreP
                .thenComparing(p -> p.url() != null ? p.url() : "");            // ascending url
    }

    private double finalScore(Product p, Map<String, Integer> boostLikeCount) {
        double base = baseMlScore(p);
        double multiplier = boostMultiplier(p, boostLikeCount);
        return base * multiplier;
    }

    /** 2. Base ML opportunity score + additive badge bonuses. */
    private double baseMlScore(Product p) {
        Product.MlScore ml = p.ml() != null ? p.ml() : Product.MlScore.EMPTY;
        double base = 100 - ml.scoreP();

        if (ml.ofertaReal()) base += BONUS_OFERTA_REAL_FLAG;
        String badge = ml.badge() != null ? ml.badge() : "";
        if ("oferta_real".equals(badge)) base += BONUS_BADGE_OFERTA_REAL;
        if ("precio_historico_bajo".equals(badge)) base += BONUS_BADGE_PRECIO_HIST_BAJO;
        if ("precio_bajo".equals(badge)) base += BONUS_BADGE_PRECIO_BAJO;

        String tendencia = ml.tendencia() != null ? ml.tendencia() : "estable";
        if (!tendencia.isBlank() && !"estable".equals(tendencia)) base += BONUS_TENDENCIA_ACTIVA;

        return base;
    }

    /** 3. Taste-boost multiplier, capped at 1.75x (5+ likes all map to the cap). */
    private double boostMultiplier(Product p, Map<String, Integer> boostLikeCount) {
        int likes = boostLikeCount.getOrDefault(OutfitService.FeedbackModel.keyOf(p), 0);
        double multiplier = 1.0 + Math.min(likes, BOOST_LIKES_CAP) * BOOST_STEP_PER_LIKE;
        return Math.min(multiplier, BOOST_MULTIPLIER_CAP);
    }
}
