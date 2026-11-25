package ar.scraper.outfits;

import ar.scraper.model.Product;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
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
        return rankPagina(productos, feedback, desde, tamanio, false);
    }

    /**
     * {@code empateComoAgrupado} ranks a list as if it had first been grouped by category (categories in order of first
     * appearance, each one keeping its own order) without building that list: a tie on score, scoreP and url, which only
     * a repeated or blank url can produce, goes to the product whose category appeared first, then to the earlier one.
     */
    public PaginaRankeada rankPagina(List<Product> productos, OutfitService.FeedbackModel feedback,
            int desde, int tamanio, boolean empateComoAgrupado) {
        if (productos == null) return new PaginaRankeada(List.of(), 0);
        OutfitService.FeedbackModel modelo = feedback != null ? feedback : OutfitService.FeedbackModel.empty();

        Set<String> exclude          = modelo.exclude();
        Set<String> excludeCategoria = modelo.excludeCategoria();
        Map<String, Integer> boostLikeCount = modelo.boostLikeCount();
        boolean usaClave = !exclude.isEmpty() || !boostLikeCount.isEmpty();

        Desempate desempate = new Desempate(productos, empateComoAgrupado);
        Comparator<Puntuado> orden = Comparator
                .comparingDouble(Puntuado::scoreNegado)
                .thenComparingInt(Puntuado::scoreP)
                .thenComparing(Puntuado::url)
                .thenComparing(desempate::comparar);

        long necesarios = Math.max(1L, (long) desde + tamanio);
        PriorityQueue<Puntuado> mejores = new PriorityQueue<>(
                (int) Math.min(necesarios, productos.size() + 1L), orden.reversed());
        int total = 0;
        for (Product p : productos) {
            // Hard veto (infantil, sport-specific) — runs FIRST, before pair/categoria exclude,
            // regardless of caller, query params or relaxation fallback.
            if (!EligibilityPolicy.elegible(p)) continue;
            String clave = usaClave ? OutfitService.FeedbackModel.keyOf(p) : null;
            if (usaClave && exclude.contains(clave)) continue;
            if (p.categoria() != null && excludeCategoria.contains(p.categoria())) continue;

            double scoreNegado = -finalScore(p, boostLikeCount, clave);
            int scoreP = p.ml() != null ? p.ml().scoreP() : 50;
            String url = StringUtils.defaultString(p.url());
            int posicion = total++;
            if (mejores.size() < necesarios) {
                mejores.add(new Puntuado(p, scoreNegado, scoreP, url, posicion));
            } else if (entraAntesQue(scoreNegado, scoreP, url, p, posicion, mejores.peek(), desempate)) {
                mejores.poll();
                mejores.add(new Puntuado(p, scoreNegado, scoreP, url, posicion));
            }
        }

        Puntuado[] ordenados = mejores.toArray(new Puntuado[0]);
        Arrays.sort(ordenados, orden);
        List<Product> pagina = new ArrayList<>();
        for (int i = desde; i < ordenados.length; i++) {
            pagina.add(ordenados[i].producto());
        }
        return new PaginaRankeada(pagina, total);
    }

    /** The comparison of the ranking order, on primitives, so a product that cannot enter allocates nothing. */
    private static boolean entraAntesQue(double scoreNegado, int scoreP, String url, Product p, int posicion,
            Puntuado peor, Desempate desempate) {
        int c = Double.compare(scoreNegado, peor.scoreNegado());
        if (c == 0) c = Integer.compare(scoreP, peor.scoreP());
        if (c == 0) c = url.compareTo(peor.url());
        if (c == 0) c = desempate.comparar(p, posicion, peor.producto(), peor.posicion());
        return c < 0;
    }

    public record PaginaRankeada(List<Product> productos, int total) {}

    private record Puntuado(Product producto, double scoreNegado, int scoreP, String url, int posicion) {}

    /**
     * Last key of the ranking: the position in the iterated list or, when the list stands for one grouped by category,
     * the position in that grouping. The category order is read from the whole list the first time a tie needs it.
     */
    private static final class Desempate {
        private final List<Product> productos;
        private final boolean agrupado;
        private Map<String, Integer> ordenDeCategoria;

        Desempate(List<Product> productos, boolean agrupado) {
            this.productos = productos;
            this.agrupado = agrupado;
        }

        int comparar(Puntuado a, Puntuado b) {
            return comparar(a.producto(), a.posicion(), b.producto(), b.posicion());
        }

        int comparar(Product a, int posicionA, Product b, int posicionB) {
            if (agrupado) {
                String categoriaA = StringUtils.defaultString(a.categoria());
                String categoriaB = StringUtils.defaultString(b.categoria());
                if (!categoriaA.equals(categoriaB)) {
                    Map<String, Integer> orden = ordenDeCategoria();
                    return Integer.compare(orden.get(categoriaA), orden.get(categoriaB));
                }
            }
            return Integer.compare(posicionA, posicionB);
        }

        private Map<String, Integer> ordenDeCategoria() {
            if (ordenDeCategoria == null) {
                Map<String, Integer> orden = new HashMap<>();
                for (Product p : productos) orden.putIfAbsent(StringUtils.defaultString(p.categoria()), orden.size());
                ordenDeCategoria = orden;
            }
            return ordenDeCategoria;
        }
    }

    private double finalScore(Product p, Map<String, Integer> boostLikeCount, String clave) {
        double base = baseMlScore(p);
        double multiplier = boostMultiplier(boostLikeCount, clave);
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

    private double boostMultiplier(Map<String, Integer> boostLikeCount, String clave) {
        int likes = clave == null ? 0 : boostLikeCount.getOrDefault(clave, 0);
        double multiplier = 1.0 + Math.min(likes, BOOST_LIKES_CAP) * BOOST_STEP_PER_LIKE;
        return Math.min(multiplier, BOOST_MULTIPLIER_CAP);
    }
}
