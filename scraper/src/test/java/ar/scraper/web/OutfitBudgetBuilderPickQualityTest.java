package ar.scraper.web;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pick quality in the budget builder ({@code OutfitBudgetBuilder}).
 *
 * <p>The MCKP objective used to be the raw {@link RecommendationService#baseMlScore},
 * which is an opportunity signal: {@code 100 - scoreP}, where {@code scoreP} is the
 * product's PRICE percentile within its categoria+genero, plus four bonuses that are
 * all price observations too. Maximizing it unbounded meant the builder was ranking
 * candidates by "how cheap is this for its category" and nothing else, so three things
 * followed that nobody asked for:</p>
 *
 * <ol>
 *   <li>a generous budget was systematically left unspent — every extra peso LOWERED
 *       the objective, so the ceiling was something the solver had an incentive to avoid;</li>
 *   <li>likes were inert: the builder called {@code baseMlScore} and never
 *       {@code finalScore}, so {@code boostLikeCount} arrived inside the FeedbackModel
 *       and was dropped. Dislikes worked (hard vetoes upstream), likes did not;</li>
 *   <li>nothing discouraged filling every slot from one brand, and the objective above
 *       actively pushed that way.</li>
 * </ol>
 *
 * <p>The random assembler ({@code OutfitService.weightedRandomPick}) already had the
 * balanced version of this — price-band proximity x likes x a CLAMPED ML factor x
 * visual coherence — so these tests pin the budget builder to the same policy rather
 * than to a fourth invented opinion.</p>
 */
@Epic("Outfit Orchestration")
@Feature("Outfit Building")
@Story("Pick quality")
@DisplayName("OutfitBudgetBuilder — pick quality")
class OutfitBudgetBuilderPickQualityTest {

    private final RecommendationService recService = new RecommendationService();
    private final OutfitService service = new OutfitService(recService);

    /** scoreP only, no badges -> baseMlScore == 100 - scoreP. */
    private Product producto(String nombre, double precio, String categoria,
                             String marca, int scoreP) {
        Product.MlScore ml = new Product.MlScore(scoreP, "", false, "estable", 50);
        return new Product("TestSitio", nombre, precio, null,
                "https://test/" + nombre.replace(" ", "-"), "https://img/test.jpg",
                categoria, "hombre", List.of(), ml, marca, "indumentaria", true);
    }

    private OutfitService.FeedbackModel sinFeedback() {
        return new OutfitService.FeedbackModel(Set.of(), Map.of(), Set.of());
    }

    private String urlElegida(OutfitService.OutfitBuilderResult r, String slot) {
        return r.slots().stream()
                .filter(s -> s.slot().equals(slot))
                .map(OutfitService.SlotPick::url)
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("a generous budget does not collapse to the cheapest product in the pool")
    void presupuestoAmplioNoTerminaEnLoMasBarato() {
        // The cheap one has the BETTER raw ML score, because a low price percentile IS
        // a high baseMlScore. That is precisely the inversion under test: for a budget
        // surface, "cheapest for its category" is not "best pick".
        Product barato = producto("Remera Barata", 10_000, "Remera", "MarcaA", 10);
        Product caro   = producto("Remera Buena",  95_000, "Remera", "MarcaB", 60);

        OutfitService.OutfitBuilderResult r = service.armarPorCategorias(
                List.of(barato, caro), List.of("Remera"), 100_000, "hombre", sinFeedback());

        assertThat(urlElegida(r, OutfitService.SUBSLOT_TORSO_BASE))
                .as("with $100.000 to spend the builder should not pick the $10.000 item")
                .isEqualTo(caro.url());
    }

    @Test
    @DisplayName("likes tilt the pick, not just dislikes")
    void losLikesInclinanLaEleccion() {
        // Same price, same ML, same category: the ONLY difference is the like.
        Product gustada = producto("Remera Gustada", 50_000, "Remera", "Querida", 40);
        Product neutra  = producto("Remera Neutra",  50_000, "Remera", "Ignota",  40);

        OutfitService.FeedbackModel feedback = new OutfitService.FeedbackModel(
                Set.of(),
                Map.of(OutfitService.FeedbackModel.keyOf(gustada), 3),
                Set.of());

        // The pool is shuffled for variety, so a single run could pass by luck.
        for (int i = 0; i < 50; i++) {
            OutfitService.OutfitBuilderResult r = service.armarPorCategorias(
                    List.of(gustada, neutra), List.of("Remera"), 50_000, "hombre", feedback);

            assertThat(urlElegida(r, OutfitService.SUBSLOT_TORSO_BASE))
                    .as("a liked marca|categoria pair should win against an equal-scoring stranger")
                    .isEqualTo(gustada.url());
        }
    }

    @Test
    @DisplayName("a slightly worse candidate wins when it avoids repeating a brand")
    void evitaRepetirMarcaEntreSlots() {
        // Equal prices neutralize the budget-usage term, isolating the brand rule.
        Product torso        = producto("Remera Uno",  50_000, "Remera",  "Uno", 20);
        Product piernasMisma = producto("Jogging Uno", 50_000, "Jogging", "Uno", 20);
        Product piernasOtra  = producto("Jogging Dos", 50_000, "Jogging", "Dos", 30);

        OutfitService.OutfitBuilderResult r = service.armarPorCategorias(
                List.of(torso, piernasMisma, piernasOtra),
                List.of("Remera", "Jogging"), 100_000, "hombre", sinFeedback());

        assertThat(urlElegida(r, OutfitService.SLOT_PIERNAS))
                .as("repeating the torso brand should cost more than the small ML gap")
                .isEqualTo(piernasOtra.url());
    }
}
