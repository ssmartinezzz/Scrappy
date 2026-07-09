package ar.scraper.web;

import ar.scraper.model.Product;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code estilo} dimension of {@link OutfitService#armarPorCategorias}
 * (9-arg overload) — the Casual vs Gym torso/piernas eligibility gate.
 *
 * Rule (confirmed with the user): casual = everything that is NOT gymrat.
 *   - estilo="casual": torso/piernas require gymrat==false
 *   - estilo="gym"   : torso/piernas require gymrat==true (pre-existing behavior)
 *   - calzado: category-driven, unaffected by estilo under either style
 */
@Epic("Outfit Orchestration")
@Feature("Supplements / Style")
@Story("Casual style")
@DisplayName("OutfitService — Casual style")
class OutfitServiceCasualStyleTest {

    private final RecommendationService recService = new RecommendationService();
    private final OutfitService service = new OutfitService(recService);

    private Product product(String nombre, double precio, String categoria,
                            String genero, String marca, int scoreP, boolean gymrat) {
        Product.MlScore ml = new Product.MlScore(scoreP, "", false, "estable", 50);
        return new Product("TestSitio", nombre, precio, null,
                "https://test/" + nombre.replace(" ", "-"), "https://img/test.jpg",
                categoria, genero, List.of(), ml, marca, "indumentaria", gymrat);
    }

    @Step("Assemble outfit for categorias={categorias} with estilo={estilo}")
    private OutfitService.OutfitBuilderResult armar(List<Product> productos,
            List<String> categorias, String estilo) {
        return service.armarPorCategorias(productos, categorias, 500_000, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(), false, List.of(), estilo);
    }

    // ── Casual includes non-gymrat torso, excludes gymrat torso ──────────────

    @Test
    void casual_selectsNonGymratTorso() {
        Product gym    = product("Remera GymRat", 10_000, "Remera", "hombre", "Nike", 5,  true);
        Product casual = product("Remera Casual", 10_000, "Remera", "hombre", "Puma", 40, false);

        var result = armar(List.of(gym, casual), List.of("Remera"), "casual");

        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).nombre()).isEqualTo("Remera Casual");
    }

    @Test
    void casual_excludesGymratTorso_evenIfOnlyOption() {
        Product gymOnly = product("Remera GymRat", 10_000, "Remera", "hombre", "Nike", 5, true);

        var result = armar(List.of(gymOnly), List.of("Remera"), "casual");

        assertThat(result.slots()).isEmpty();
        assertThat(result.categoriasVacias()).contains("Remera");
    }

    // ── Casual includes non-gymrat piernas, excludes gymrat piernas ──────────

    @Test
    void casual_selectsNonGymratPiernas() {
        Product gym    = product("Jogging GymRat", 8_000, "Jogging", "hombre", "Nike", 5,  true);
        Product casual = product("Jean Casual",    8_000, "Jean",    "hombre", "Levis", 40, false);

        var result = armar(List.of(gym, casual), List.of("Jogging", "Jean"), "casual");

        // Both resolve to the single "piernas" sub-slot; only the non-gymrat Jean is eligible.
        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).nombre()).isEqualTo("Jean Casual");
    }

    // ── Calzado is unaffected by estilo (category-driven) ────────────────────

    @Test
    void casual_calzadoEligibleRegardlessOfGymrat() {
        Product zapa = product("Zapatilla Urbana", 20_000, "Zapatilla Urbana", "hombre", "Nike", 10, false);

        var result = armar(List.of(zapa), List.of("Zapatilla Urbana"), "casual");

        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).nombre()).isEqualTo("Zapatilla Urbana");
    }

    // ── Gym gate is preserved under the 9-arg overload (regression) ──────────

    @Test
    void gym_stillRequiresGymratTorso() {
        Product gym    = product("Remera GymRat", 10_000, "Remera", "hombre", "Nike", 40, true);
        Product casual = product("Remera Casual", 10_000, "Remera", "hombre", "Puma", 5,  false);

        var result = armar(List.of(gym, casual), List.of("Remera"), "gym");

        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).nombre()).isEqualTo("Remera GymRat");
    }

    // ── Greedy path honors the casual gate too ───────────────────────────────

    @Test
    void casual_greedyExcludesGymratTorso() {
        Product gym    = product("Buzo GymRat", 10_000, "Buzo", "hombre", "Nike", 5,  true);
        Product casual = product("Buzo Casual", 10_000, "Buzo", "hombre", "Puma", 40, false);
        Allure.parameter("estilo", "casual");
        Allure.parameter("greedy", true);

        var result = service.armarPorCategorias(List.of(gym, casual), List.of("Buzo"),
                500_000, "hombre", OutfitService.FeedbackModel.empty(),
                Set.of(), true, List.of(), "casual");

        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).nombre()).isEqualTo("Buzo Casual");
    }
}
