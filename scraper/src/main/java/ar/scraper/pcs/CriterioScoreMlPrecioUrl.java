package ar.scraper.pcs;

import ar.scraper.model.Product;
import ar.scraper.outfits.RecommendationService;

import java.util.Comparator;
import java.util.List;

/** rank -baseMlScore desc, precio asc, url asc — same tiebreak as the "Para ti" feed. */
public class CriterioScoreMlPrecioUrl implements CriterioDeSeleccion {

    private final RecommendationService recommendationService;

    public CriterioScoreMlPrecioUrl(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @Override
    public Product elegir(List<Product> candidatos) {
        return candidatos.stream()
                .min(Comparator
                        .comparingDouble((Product p) -> -recommendationService.baseMlScore(p))
                        .thenComparingDouble(Product::precio)
                        .thenComparing(CriterioScoreMlPrecioUrl::urlDe))
                .orElseThrow();
    }

    private static String urlDe(Product p) {
        return p.url() != null ? p.url() : "";
    }
}
