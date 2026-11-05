package ar.scraper.outfits;

import ar.scraper.model.Product;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/**
 * Pure, stateless, DB-agnostic — mirrors {@link OutfitService}'s style: no Spring deps, no caching,
 * deterministic per-request full-scan rank over the live in-memory catalog (same cost class as
 * {@code /api/mejores}).
 */
public class RecommendationService {

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
        return rankPagina(productos, feedback, 0, Integer.MAX_VALUE).productos();
    }

    public PaginaRankeada rankPagina(List<Product> productos, OutfitService.FeedbackModel feedback,
            int desde, int tamanio) {
        if (productos == null) return new PaginaRankeada(List.of(), 0);
        OutfitService.FeedbackModel modelo = feedback != null ? feedback : OutfitService.FeedbackModel.empty();

        Set<String> exclude          = modelo.exclude();
        Set<String> excludeCategoria = modelo.excludeCategoria();
        Map<String, Integer> boostLikeCount = modelo.boostLikeCount();

        long necesarios = Math.max(1L, (long) desde + tamanio);
        PriorityQueue<Puntuado> mejores = new PriorityQueue<>(
                (int) Math.min(necesarios, productos.size() + 1L), ORDEN.reversed());
        int total = 0;
        for (Product p : productos) {
            // Hard veto (infantil, sport-specific) — runs FIRST, before pair/categoria exclude,
            // regardless of caller, query params or relaxation fallback.
            if (!EligibilityPolicy.elegible(p)) continue;
            if (exclude.contains(OutfitService.FeedbackModel.keyOf(p))) continue;
            if (p.categoria() != null && excludeCategoria.contains(p.categoria())) continue;

            Puntuado candidato = new Puntuado(p, -finalScore(p, boostLikeCount),
                    p.ml() != null ? p.ml().scoreP() : 50, StringUtils.defaultString(p.url()), total++);
            if (mejores.size() < necesarios) {
                mejores.add(candidato);
            } else if (ORDEN.compare(candidato, mejores.peek()) < 0) {
                mejores.poll();
                mejores.add(candidato);
            }
        }

        Puntuado[] ordenados = mejores.toArray(new Puntuado[0]);
        Arrays.sort(ordenados, ORDEN);
        List<Product> pagina = new ArrayList<>();
        for (int i = desde; i < ordenados.length; i++) {
            pagina.add(ordenados[i].producto());
        }
        return new PaginaRankeada(pagina, total);
    }

    public record PaginaRankeada(List<Product> productos, int total) {}

    private record Puntuado(Product producto, double scoreNegado, int scoreP, String url, int posicion) {}

    private static final Comparator<Puntuado> ORDEN = Comparator
            .comparingDouble(Puntuado::scoreNegado)
            .thenComparingInt(Puntuado::scoreP)
            .thenComparing(Puntuado::url)
            .thenComparingInt(Puntuado::posicion);

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
