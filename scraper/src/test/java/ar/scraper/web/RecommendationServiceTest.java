package ar.scraper.web;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RecommendationService}, the ranking core of the
 * "Para ti" feed (design.md Decision 3 — veto -> base ML score + badge
 * bonuses -> taste-boost multiplier capped at 1.75x -> deterministic sort).
 */
@Epic("Outfit Orchestration")
@Feature("Recommendations")
@DisplayName("RecommendationService — Para ti ranking")
class RecommendationServiceTest {

    private final RecommendationService service = new RecommendationService();

    private Product producto(String nombre, String url, String categoria, String marca,
                              int scoreP, String badge, boolean ofertaReal, String tendencia) {
        return producto(nombre, url, categoria, marca, scoreP, badge, ofertaReal, tendencia, "hombre");
    }

    private Product producto(String nombre, String url, String categoria, String marca,
                              int scoreP, String badge, boolean ofertaReal, String tendencia,
                              String genero) {
        return new Product("TestSitio", nombre, 10000, null, url, "https://img/test.jpg",
                categoria, genero, List.of(),
                new Product.MlScore(scoreP, badge, ofertaReal, tendencia, scoreP, 0.0, "standard"),
                marca, "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1);
    }

    @Test
    void pairExcludeRemovesProductRegardlessOfOtherCandidates() {
        Product nikeZapatilla = producto("Zapatilla Nike", "https://t/1", "Zapatilla", "Nike", 50, "", false, "estable");
        Product adidasZapatilla = producto("Zapatilla Adidas", "https://t/2", "Zapatilla", "Adidas", 50, "", false, "estable");

        OutfitService.FeedbackModel feedback = new OutfitService.FeedbackModel(
                Set.of("Nike|Zapatilla"), Map.of(), Set.of());

        List<Product> ranked = service.rank(List.of(nikeZapatilla, adidasZapatilla), feedback);

        assertThat(ranked).noneMatch(p -> "Nike".equals(p.marca()) && "Zapatilla".equals(p.categoria()));
        assertThat(ranked).containsExactly(adidasZapatilla);
    }

    @Test
    void categoriaWideExcludeRemovesWholeCategoriaRegardlessOfMarcaIncludingUnbranded() {
        Product antiparrasOakley = producto("Antiparras Oakley", "https://t/1", "Antiparras", "Oakley", 50, "", false, "estable");
        Product antiparrasNoMarca = producto("Antiparras genericas", "https://t/2", "Antiparras", "", 50, "", false, "estable");
        Product remeraNoMarca = producto("Remera generica", "https://t/3", "Remera", "", 50, "", false, "estable");

        OutfitService.FeedbackModel feedback = new OutfitService.FeedbackModel(
                Set.of(), Map.of(), Set.of("Antiparras"));

        List<Product> ranked = service.rank(
                List.of(antiparrasOakley, antiparrasNoMarca, remeraNoMarca), feedback);

        assertThat(ranked).noneMatch(p -> "Antiparras".equals(p.categoria()));
        // unbranded products of OTHER categories remain eligible
        assertThat(ranked).contains(remeraNoMarca);
    }

    @Test
    void boostedPairRanksHigherThanEquivalentNonBoostedPair() {
        Product adidasRemera = producto("Remera Adidas", "https://t/1", "Remera", "Adidas", 50, "", false, "estable");
        Product nikeRemera   = producto("Remera Nike",   "https://t/2", "Remera", "Nike",   50, "", false, "estable");

        OutfitService.FeedbackModel feedback = new OutfitService.FeedbackModel(
                Set.of(), Map.of("Adidas|Remera", 2), Set.of());

        List<Product> ranked = service.rank(List.of(nikeRemera, adidasRemera), feedback);

        assertThat(ranked.get(0)).isEqualTo(adidasRemera);
        assertThat(ranked.get(1)).isEqualTo(nikeRemera);
    }

    @Test
    void boostMultiplierIsCappedAtFiveLikesEquivalent() {
        Product fiveLikes = producto("Remera A", "https://t/1", "Remera", "Adidas", 50, "", false, "estable");
        Product manyLikes = producto("Remera B", "https://t/2", "Remera", "Puma", 50, "", false, "estable");

        OutfitService.FeedbackModel feedbackFive = new OutfitService.FeedbackModel(
                Set.of(), Map.of("Adidas|Remera", 5, "Puma|Remera", 5), Set.of());
        OutfitService.FeedbackModel feedbackMore = new OutfitService.FeedbackModel(
                Set.of(), Map.of("Adidas|Remera", 5, "Puma|Remera", 50), Set.of());

        List<Product> rankedFive = service.rank(List.of(fiveLikes, manyLikes), feedbackFive);
        List<Product> rankedMore = service.rank(List.of(fiveLikes, manyLikes), feedbackMore);

        // Both at 5+ likes get the same capped 1.75x multiplier -> same relative
        // order as when both are exactly at 5 likes (tiebreak by scoreP/url).
        assertThat(rankedFive).isEqualTo(rankedMore);
    }

    @Test
    void coldStartWithEmptyFeedbackModelProducesPureMlOrderWithNoError() {
        Product better = producto("Producto mejor", "https://t/1", "Remera", "Nike", 10, "", false, "estable");
        Product worse  = producto("Producto peor",  "https://t/2", "Remera", "Adidas", 90, "", false, "estable");

        List<Product> ranked = service.rank(List.of(worse, better), OutfitService.FeedbackModel.empty());

        assertThat(ranked).containsExactly(better, worse);
    }

    @Test
    void tiebreakOnEqualFinalScoreSortsByAscendingScorePThenUrl() {
        Product a = producto("A", "https://z/2", "Remera", "Nike", 50, "", false, "estable");
        Product b = producto("B", "https://a/1", "Remera", "Nike", 50, "", false, "estable");

        List<Product> ranked = service.rank(List.of(a, b), OutfitService.FeedbackModel.empty());

        // Equal scoreP -> tiebreak by url ascending
        assertThat(ranked).containsExactly(b, a);
    }

    @Test
    void ofertaRealAndBadgeBonusesIncreaseRankOverPlainEquivalentScoreP() {
        Product plain  = producto("Plain", "https://t/1", "Remera", "Nike", 50, "", false, "estable");
        Product oferta = producto("Oferta", "https://t/2", "Remera", "Nike", 50, "oferta_real", true, "estable");

        List<Product> ranked = service.rank(List.of(plain, oferta), OutfitService.FeedbackModel.empty());

        assertThat(ranked.get(0)).isEqualTo(oferta);
    }

    @Test
    void infantilProductIsHardVetoedEvenAmongMixedGeneroCandidates() {
        Product infantil = producto("Zapatilla Niño", "https://t/1", "Zapatilla", "Nike",
                50, "", false, "estable", "infantil");
        Product hombre   = producto("Zapatilla Hombre", "https://t/2", "Zapatilla", "Adidas",
                50, "", false, "estable", "hombre");
        Product mujer    = producto("Zapatilla Mujer", "https://t/3", "Zapatilla", "Puma",
                50, "", false, "estable", "mujer");
        Product unisex   = producto("Zapatilla Unisex", "https://t/4", "Zapatilla", "Vans",
                50, "", false, "estable", "unisex");

        List<Product> ranked = service.rank(
                List.of(infantil, hombre, mujer, unisex), OutfitService.FeedbackModel.empty());

        assertThat(ranked).noneMatch(p -> "infantil".equalsIgnoreCase(p.genero()));
        assertThat(ranked).hasSize(3);
        assertThat(ranked).containsExactlyInAnyOrder(hombre, mujer, unisex);
    }
}
