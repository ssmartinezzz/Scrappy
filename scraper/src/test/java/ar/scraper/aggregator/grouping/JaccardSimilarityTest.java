package ar.scraper.aggregator.grouping;

import ar.scraper.model.Product;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static ar.scraper.aggregator.grouping.GroupingTestFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JaccardSimilarity}, extracted from
 * {@code GroupingService.subAgruparPorJaccard}/{@code jaccardSimilarity}/
 * {@code palabrasSignificativas} (Work Unit 2 of the aggregator SOLID
 * modularization).
 */
@Epic("Aggregation & Grouping")
@Feature("Grouping")
@Story("Jaccard similarity")
@DisplayName("JaccardSimilarity — bag-of-words threshold matching")
class JaccardSimilarityTest {

    private final JaccardSimilarity jaccard = new JaccardSimilarity();

    // ── jaccardSimilarity() threshold boundary ──────────────────────────────

    @Test
    void identicalWordSetsHaveSimilarityOne() {
        Set<String> words = Set.of("nike", "air", "force");
        Allure.parameter("a", words);
        Allure.parameter("b", words);
        assertThat(jaccard.jaccardSimilarity(words, words)).isEqualTo(1.0);
    }

    @Test
    void airForceVsAirMaxFallsBelowThreshold() {
        Set<String> forceWords = Set.of("nike", "air", "force");
        Set<String> maxWords = Set.of("nike", "air", "max");
        Allure.parameter("a", forceWords);
        Allure.parameter("b", maxWords);

        // |{nike,air}| / |{nike,air,force,max}| = 2/4 = 0.5 < 0.55 threshold
        assertThat(jaccard.jaccardSimilarity(forceWords, maxWords)).isEqualTo(0.5);
    }

    @Test
    void bothEmptySetsAreConsideredIdentical() {
        Allure.parameter("a", Set.of());
        Allure.parameter("b", Set.of());
        assertThat(jaccard.jaccardSimilarity(Set.of(), Set.of())).isEqualTo(1.0);
    }

    @Test
    void oneEmptySetYieldsZeroSimilarity() {
        Allure.parameter("a", Set.of("nike"));
        Allure.parameter("b", Set.of());
        assertThat(jaccard.jaccardSimilarity(Set.of("nike"), Set.of())).isEqualTo(0.0);
    }

    // ── palabrasSignificativas() tokenization ───────────────────────────────

    @Test
    void palabrasSignificativasFiltersStopWordsAndShortTokens() {
        Product p = product("VCP", "Nike Air Force 1 Blanco Talle 42", "Nike", "Zapatilla Urbana", 100000);

        Set<String> words = jaccard.palabrasSignificativas(p);

        assertThat(words).contains("nike", "air", "force")
                .doesNotContain("blanco", "talle", "1", "42");
    }

    @Test
    void palabrasSignificativasNormalizesAccents() {
        Product p = product("VCP", "Pantalón Cargo", "Adidás", "Pantalon", 50000);

        Set<String> words = jaccard.palabrasSignificativas(p);

        assertThat(words).contains("pantalon", "cargo", "adidas");
    }

    // ── subAgruparPorJaccard() cross-site sub-grouping ──────────────────────

    @Test
    void subAgruparMergesCrossSiteProductsAboveThreshold() {
        Product a = product("VCP", "Nike Air Force 1 Blanco", "Nike", "Zapatilla Urbana", 100000);
        Product b = product("Freres", "Nike Air Force 1 Negro", "Nike", "Zapatilla Urbana", 90000);

        List<List<Product>> grupos = jaccard.subAgruparPorJaccard(List.of(a, b));

        assertThat(grupos).hasSize(1);
        assertThat(grupos.get(0)).hasSize(2);
    }

    @Test
    void subAgruparNeverMergesSameSiteProducts() {
        Product a = product("VCP", "Nike Air Force 1 Blanco", "Nike", "Zapatilla Urbana", 100000);
        Product b = product("VCP", "Nike Air Force 1 Blanco", "Nike", "Zapatilla Urbana", 100000);

        List<List<Product>> grupos = jaccard.subAgruparPorJaccard(List.of(a, b));

        assertThat(grupos).hasSize(2);
    }
}
