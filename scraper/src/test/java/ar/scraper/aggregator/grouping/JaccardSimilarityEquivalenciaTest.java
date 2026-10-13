package ar.scraper.aggregator.grouping;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link JaccardSimilarity#jaccardSimilarity} against the original set-based formula.
 * The sub-grouping oracle was removed when the clustering algorithm intentionally changed.
 */
@Epic("Aggregation & Grouping")
@Feature("Comparador multi-sitio")
@Story("Equivalencia de la similitud Jaccard")
@DisplayName("JaccardSimilarity — similitud idéntica a la fórmula original")
class JaccardSimilarityEquivalenciaTest {

    private final JaccardSimilarity jaccard = new JaccardSimilarity();

    private double jaccardReferencia(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> union        = new HashSet<>(a); union.addAll(b);
        Set<String> intersection = new HashSet<>(a); intersection.retainAll(b);
        return (double) intersection.size() / union.size();
    }

    // ─── The similarity function itself ──────────────────────────────────────

    @Test
    @DisplayName("la similitud coincide con la fórmula original en todos los solapamientos")
    void laSimilitudCoincideConLaFormulaOriginal() {
        Random rng = new Random(5);
        List<String> vocabulario = List.of("nike", "air", "force", "max", "negro", "blanco",
                                           "urbana", "running", "clasica", "retro");
        for (int caso = 0; caso < 500; caso++) {
            Set<String> a = new HashSet<>(), b = new HashSet<>();
            for (String t : vocabulario) {
                if (rng.nextBoolean()) a.add(t);
                if (rng.nextBoolean()) b.add(t);
            }
            assertThat(jaccard.jaccardSimilarity(a, b))
                    .as("a=%s b=%s", a, b)
                    .isEqualTo(jaccardReferencia(a, b));
        }
    }

    @Test
    @DisplayName("dos sets vacíos siguen siendo idénticos y uno vacío sigue dando cero")
    void semanticaDeSetsVacios() {
        assertThat(jaccard.jaccardSimilarity(Set.of(), Set.of())).isEqualTo(1.0);
        assertThat(jaccard.jaccardSimilarity(Set.of("nike"), Set.of())).isEqualTo(0.0);
        assertThat(jaccard.jaccardSimilarity(Set.of(), Set.of("nike"))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("es simétrica: da lo mismo el orden de los argumentos")
    void esSimetrica() {
        Set<String> a = Set.of("nike", "air", "force");
        Set<String> b = Set.of("nike", "air", "max", "retro");
        assertThat(jaccard.jaccardSimilarity(a, b)).isEqualTo(jaccard.jaccardSimilarity(b, a));
    }
}
