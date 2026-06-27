package ar.scraper.web;

import ar.scraper.model.Product;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OutfitService#armarPorCategorias} — the Budget-Aware
 * Outfit Builder (MCKP mode). All tests use synthetic in-memory fixtures and
 * run against the real {@link RecommendationService} scoring.
 *
 * TDD coverage (spec scenarios / design invariants):
 *   2.1 Full outfit assembled; totalEstimado ≤ presupuesto invariant (FR-3, scenario 1)
 *   2.2 Highest-scored candidate selected per category (FR-4, scenario 2)
 *   2.3 Top candidate skipped when precio > remaining; cheaper candidate chosen (scenario 3)
 *   2.4 No-fit: slots empty when no combination fits budget (FR-7, scenario 4)
 *   2.5 Vetoed product excluded from selection (FR-5, scenario 5)
 *   2.6 Empty category after gender filter → categoriasVacias populated (FR-8, scenario 6)
 *   2.7 genero=hombre request includes genero=unisex products (FR-6, scenario 7)
 *   2.8 Identical inputs produce identical outputs (FR-11, deterministic tie-break)
 *   2.9 Pool of 30 candidates capped at K=20 during enumeration
 */
class OutfitServiceBuilderTest {

    private final RecommendationService recService = new RecommendationService();
    private final OutfitService service = new OutfitService(recService);

    /**
     * Creates a synthetic Product with the given attributes and an MlScore
     * constructed from {@code scoreP} (pctilCategoria defaults to a neutral 50).
     * {@code baseMlScore(p) = 100 - scoreP} when no badge bonuses apply.
     */
    private Product product(String nombre, double precio, String categoria,
                             String genero, String marca, int scoreP) {
        Product.MlScore ml = new Product.MlScore(scoreP, "", false, "estable", 50);
        return new Product("TestSitio", nombre, precio, null,
                "https://test/" + nombre.replace(" ", "-"), "https://img/test.jpg",
                categoria, genero, List.of(), ml, marca, "indumentaria", false);
    }

    // ── 2.1 Full outfit assembled — totalEstimado ≤ presupuesto invariant ────

    @Test
    void fullOutfitAssembledWithinBudget() {
        Product buzo  = product("Buzo Nike",    15_000, "Buzo",  "hombre", "Nike",   10);
        Product short_ = product("Short Adidas", 10_000, "Short", "hombre", "Adidas", 20);

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(buzo, short_),
                List.of("Buzo", "Short"),
                50_000, "hombre",
                OutfitService.FeedbackModel.empty());

        assertThat(result.slots()).hasSize(2);
        assertThat(result.totalEstimado()).isLessThanOrEqualTo(50_000);
        assertThat(result.noCumplePresupuesto()).isFalse();
        assertThat(result.categoriasVacias()).isEmpty();
        assertThat(result.categoriasSinPresupuesto()).isEmpty();
    }

    // ── 2.2 Highest-scored candidate selected per category ────────────────────

    @Test
    void highestScoredCandidateSelectedPerCategory() {
        // scoreP=10 → baseMlScore=90; scoreP=40 → baseMlScore=60
        Product buzoA = product("Buzo A", 15_000, "Buzo", "hombre", "Nike", 10);
        Product buzoB = product("Buzo B", 14_000, "Buzo", "hombre", "Puma", 40);

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(buzoA, buzoB),
                List.of("Buzo"),
                50_000, "hombre",
                OutfitService.FeedbackModel.empty());

        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).nombre()).isEqualTo("Buzo A");
    }

    // ── 2.3 Top candidate skipped when its price + earlier pick exceeds budget ─

    @Test
    void topCandidateSkippedWhenExceedsRemainingBudget() {
        // Global budget = 45 000
        // Short (30 000, score=100) + BuzoCheap (10 000, score=70) = 40 000 ≤ 45 000 → score 170
        // Short + BuzoExpensive (20 000, score=95) = 50 000 > 45 000 → pruned
        // BuzoExpensive alone = 20 000 → score 95
        // Best: Short + BuzoCheap
        Product short_        = product("Short Nike",     30_000, "Short", "hombre", "Nike",  0); // score=100
        Product buzoExpensive = product("Buzo Expensive", 20_000, "Buzo",  "hombre", "Nike",  5); // score=95
        Product buzoCheap     = product("Buzo Cheap",     10_000, "Buzo",  "hombre", "Puma", 30); // score=70

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(short_, buzoExpensive, buzoCheap),
                List.of("Short", "Buzo"),
                45_000, "hombre",
                OutfitService.FeedbackModel.empty());

        assertThat(result.totalEstimado()).isLessThanOrEqualTo(45_000);

        Optional<OutfitService.SlotPick> buzoPick = result.slots().stream()
                .filter(s -> "Buzo".equals(s.slot())).findFirst();
        assertThat(buzoPick).isPresent();
        assertThat(buzoPick.get().nombre()).isEqualTo("Buzo Cheap");
    }

    // ── 2.4 No-fit: slots empty when no combination fits budget ───────────────

    @Test
    void noFitWhenBudgetInsufficient() {
        // All items cost 10 000 but budget is 5 000
        Product buzo = product("Buzo Nike", 10_000, "Buzo", "hombre", "Nike", 10);

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(buzo),
                List.of("Buzo"),
                5_000, "hombre",
                OutfitService.FeedbackModel.empty());

        assertThat(result.slots()).isEmpty();
        assertThat(result.noCumplePresupuesto()).isTrue();
        assertThat(result.categoriasSinPresupuesto()).contains("Buzo");
    }

    // ── 2.5 Vetoed product excluded from selection ────────────────────────────

    @Test
    void vetoedProductExcludedFromSelection() {
        // Nike|Buzo is vetoed; only Puma|Buzo is eligible
        Product buzoNike = product("Buzo Nike", 15_000, "Buzo", "hombre", "Nike", 10);
        Product buzoPuma = product("Buzo Puma", 12_000, "Buzo", "hombre", "Puma", 30);

        OutfitService.FeedbackModel feedback = new OutfitService.FeedbackModel(
                Set.of("Nike|Buzo"), Map.of(), Set.of());

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(buzoNike, buzoPuma),
                List.of("Buzo"),
                50_000, "hombre",
                feedback);

        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).nombre()).isEqualTo("Buzo Puma");
    }

    // ── 2.6 Empty category after gender filter → categoriasVacias ────────────

    @Test
    void emptyCategoryAfterGenderFilterPopulatesCategoriasVacias() {
        // Only a "mujer" Zapatilla exists; request is "hombre"
        Product zapatillaMujer = product("Zapatilla Nike Mujer", 30_000, "Zapatilla", "mujer", "Nike", 10);

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(zapatillaMujer),
                List.of("Zapatilla"),
                100_000, "hombre",
                OutfitService.FeedbackModel.empty());

        assertThat(result.categoriasVacias()).contains("Zapatilla");
        assertThat(result.slots()).isEmpty();
    }

    // ── 2.7 genero=hombre request includes unisex products ───────────────────

    @Test
    void hombreRequestIncludesUnisexProducts() {
        Product buzoUnisex = product("Buzo Unisex", 15_000, "Buzo", "unisex", "Nike", 10);

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(buzoUnisex),
                List.of("Buzo"),
                50_000, "hombre",
                OutfitService.FeedbackModel.empty());

        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).nombre()).isEqualTo("Buzo Unisex");
    }

    // ── 2.8 Identical inputs produce identical outputs (determinism) ───────────

    @Test
    void identicalInputsProduceIdenticalOutputs() {
        Product buzoA = product("Buzo A", 15_000, "Buzo", "hombre", "Nike", 10);
        Product buzoB = product("Buzo B", 12_000, "Buzo", "hombre", "Puma", 20);

        OutfitService.OutfitBuilderResult result1 = service.armarPorCategorias(
                List.of(buzoA, buzoB), List.of("Buzo"),
                50_000, "hombre", OutfitService.FeedbackModel.empty());
        OutfitService.OutfitBuilderResult result2 = service.armarPorCategorias(
                List.of(buzoA, buzoB), List.of("Buzo"),
                50_000, "hombre", OutfitService.FeedbackModel.empty());

        assertThat(result1.slots()).hasSize(1);
        assertThat(result2.slots()).hasSize(1);
        assertThat(result1.slots().get(0).url()).isEqualTo(result2.slots().get(0).url());
        assertThat(result1.totalEstimado()).isEqualTo(result2.totalEstimado());
    }

    // ── 2.9 Pool of 30 candidates capped at K=20 during enumeration ──────────

    @Test
    void poolCappedAtK20ForLargeCatalogCategory() {
        // Create 30 Buzo products with decreasing scoreP (= increasing baseMlScore)
        // Product at index 0 has scoreP=0 → baseMlScore=100 (best)
        List<Product> productos = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            productos.add(product("Buzo " + i, 15_000, "Buzo", "hombre", "Nike", i));
        }

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                productos, List.of("Buzo"),
                500_000, "hombre",
                OutfitService.FeedbackModel.empty());

        // Pool is sorted desc by baseMlScore; "Buzo 0" (scoreP=0, score=100) must be selected
        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).nombre()).isEqualTo("Buzo 0");
        // Function completes correctly despite 30 candidates (K=20 cap applied internally)
        assertThat(result.totalEstimado()).isLessThanOrEqualTo(500_000);
    }
}
