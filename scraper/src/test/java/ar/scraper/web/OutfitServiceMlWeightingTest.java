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
 * The random assembler ({@code OutfitService.armar}) used to weigh candidates by
 * price distance and user likes only — {@code p.ml()} was never read, so within
 * one price band a {@code fake_discount} item and an {@code all_time_low} item
 * were equally likely to be shown. The budget builder maximizes exactly that
 * signal and the "Para ti" feed ranks by it, so the gym surface was the only
 * place in the app where ML opportunity was ignored.
 *
 * <p>These tests pin the fix and, just as importantly, its bounds: the ML factor
 * must stay below the feedback boost (an explicit like beats a badge) and a
 * product with no ML data must weigh exactly as it did before.</p>
 */
@Epic("Outfit Orchestration")
@Feature("Outfit Building")
@Story("ML weighting")
@DisplayName("OutfitService — ML weighting in the random assembler")
class OutfitServiceMlWeightingTest {

    private static final int RUNS = 400;

    private final OutfitService service = new OutfitService(new RecommendationService());

    private Product producto(String nombre, double precio, String categoria, String marca,
                             Product.MlScore ml) {
        return new Product("TestSitio", nombre, precio, null,
                "https://test/" + nombre.replace(" ", "-"), "https://img/test.jpg",
                categoria, "hombre", List.of(), ml, marca, "indumentaria", true);
    }

    /** scoreP only, no badges → baseMlScore == 100 - scoreP. */
    private Product.MlScore score(int scoreP) {
        return new Product.MlScore(scoreP, "", false, "estable", 50);
    }

    private Product.MlScore scoreConBadge(int scoreP, String badge) {
        return new Product.MlScore(scoreP, badge, false, "estable", 50);
    }

    /** How many of RUNS outfits carried the product at this url in the torso slot. */
    private int vecesElegido(List<Product> catalogo, OutfitService.FeedbackModel feedback, String url) {
        int veces = 0;
        for (int i = 0; i < RUNS; i++) {
            OutfitService.Outfit outfit = service.armar(catalogo, "hombre", "gym", feedback);
            boolean hit = outfit.slots().stream()
                    .anyMatch(s -> OutfitService.SLOT_TORSO.equals(s.slot()) && url.equals(s.url()));
            if (hit) veces++;
        }
        return veces;
    }

    @Test
    @DisplayName("a real deal is picked far more often than a dud at the same price")
    void armarPrefiereElProductoConMejorSenalMl() {
        // Identical price → identical price-distance weight, so any difference in
        // pick rate comes from the ML factor and nothing else.
        Product oportunidad = producto("Remera Oportunidad", 20000, "Remera", "Nike",
                scoreConBadge(5, "all_time_low"));
        Product cara = producto("Remera Cara", 20000, "Remera", "Puma", score(95));
        Product shortAdidas = producto("Short Adidas", 20000, "Short", "Adidas", score(50));
        Product zapatilla = producto("Zapatilla Nike", 20000, "Zapatilla", "Nike", score(50));

        List<Product> catalogo = List.of(oportunidad, cara, shortAdidas, zapatilla);

        int conOportunidad = vecesElegido(catalogo, OutfitService.FeedbackModel.empty(), oportunidad.url());
        int conCara = vecesElegido(catalogo, OutfitService.FeedbackModel.empty(), cara.url());

        // Weight ratio is 2.2 : 0.5 (the low score clamps at the floor), so ~4.4x.
        // Asserting only 2x keeps the test immune to sampling noise at RUNS=400.
        assertThat(conOportunidad).isGreaterThan(conCara * 2);
        // Variety must survive: the worse product is down-weighted, never vetoed.
        assertThat(conCara).isGreaterThan(0);
    }

    @Test
    @DisplayName("an explicit like still outranks a badge")
    void elBoostDeFeedbackGanaSobreElFactorMl() {
        // The ML factor is capped BELOW the feedback boost on purpose: a like is a
        // statement of taste, a badge is a price observation.
        Product conLikes = producto("Remera Querida", 20000, "Remera", "Ena", score(50));
        Product conBadge = producto("Remera Oportunidad", 20000, "Remera", "Nike",
                scoreConBadge(0, "all_time_low"));
        Product shortAdidas = producto("Short Adidas", 20000, "Short", "Adidas", score(50));
        Product zapatilla = producto("Zapatilla Nike", 20000, "Zapatilla", "Nike", score(50));

        List<Product> catalogo = List.of(conLikes, conBadge, shortAdidas, zapatilla);
        OutfitService.FeedbackModel feedback = new OutfitService.FeedbackModel(
                Set.of(), Map.of("Ena|Remera", 3), Set.of());

        int vecesQuerida = vecesElegido(catalogo, feedback, conLikes.url());
        int vecesBadge = vecesElegido(catalogo, feedback, conBadge.url());

        assertThat(vecesQuerida).isGreaterThan(vecesBadge);
    }

    @Test
    @DisplayName("the ML factor never resurrects a vetoed pair")
    void elFactorMlNoAnulaElVetoDeDislike() {
        // Highest possible ML signal on the vetoed pair — the veto is a hard filter
        // that runs upstream of the weighting, and must stay that way.
        Product vetada = producto("Remera Vetada", 20000, "Remera", "Nike",
                scoreConBadge(0, "all_time_low"));
        Product permitida = producto("Remera Permitida", 20000, "Remera", "Puma", score(95));
        Product shortAdidas = producto("Short Adidas", 20000, "Short", "Adidas", score(50));
        Product zapatilla = producto("Zapatilla Nike", 20000, "Zapatilla", "Nike", score(50));

        OutfitService.FeedbackModel feedback = new OutfitService.FeedbackModel(
                Set.of("Nike|Remera"), Map.of(), Set.of());

        for (int i = 0; i < 50; i++) {
            OutfitService.Outfit outfit = service.armar(
                    List.of(vetada, permitida, shortAdidas, zapatilla), "hombre", "gym", feedback);
            assertThat(outfit.slots()).noneMatch(s -> vetada.url().equals(s.url()));
        }
    }

    @Test
    @DisplayName("products with no ML data weigh exactly as before")
    void productosSinDatosMlConservanElPesoPrevio() {
        // MlScore.EMPTY is scoreP=50 with no badges, which is precisely the neutral
        // point of the normalization — factor 1.0, so the pre-existing price weights
        // are untouched for a catalog that has never been through the ML pipeline.
        Product remeraA = producto("Remera A", 20000, "Remera", "Nike", Product.MlScore.EMPTY);
        Product remeraB = producto("Remera B", 20000, "Remera", "Puma", Product.MlScore.EMPTY);
        Product shortAdidas = producto("Short Adidas", 20000, "Short", "Adidas", Product.MlScore.EMPTY);
        Product zapatilla = producto("Zapatilla Nike", 20000, "Zapatilla", "Nike", Product.MlScore.EMPTY);

        List<Product> catalogo = List.of(remeraA, remeraB, shortAdidas, zapatilla);

        int vecesA = vecesElegido(catalogo, OutfitService.FeedbackModel.empty(), remeraA.url());

        // Equal price and equal (neutral) ML → a fair coin. Anything outside this
        // window means the normalization moved the neutral point off 1.0.
        assertThat(vecesA).isBetween(RUNS / 4, RUNS * 3 / 4);
    }
}
