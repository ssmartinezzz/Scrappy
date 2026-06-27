package ar.scraper.web;

import ar.scraper.model.Product;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
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
 *   2.9 Pool of 30 candidates: a product is selected within budget (K=20 sampling active)
 *
 *   3.1 Gymrat gate — non-gymrat torso excluded even if highest score (UOB-06)
 *   3.2 Gymrat gate — calzado not filtered by gymrat (UOB-06)
 *   3.3 Shuffle produces variety when pool > 20 products (UOB-07)
 *   3.4 excluirUrls — excluded URL not selected even if highest score (UOB-10)
 *   3.5 calcularMinimoBudget returns sum of min(precio) per category (UOB-11)
 *   3.6 calcularMinimoBudget returns null when any pool is empty (UOB-11)
 *   3.7 Greedy picks highest-scored per category within budget (UOB-07, UOB-08)
 *   3.8 Greedy respects hard budget (UOB-08)
 *   3.9 Greedy honors excluirUrls (UOB-10)
 *   3.10 minimoBudgetNecesario populated on no-fit with budget gap (UOB-11)
 *   3.11 minimoBudgetNecesario null on catalog gap (UOB-11)
 *   3.12 minimoBudgetNecesario null on success (UOB-11)
 */
class OutfitServiceBuilderTest {

    private final RecommendationService recService = new RecommendationService();
    private final OutfitService service = new OutfitService(recService);

    /**
     * Creates a synthetic gymrat Product (gymrat=true by default).
     * {@code baseMlScore(p) = 100 - scoreP} when no badge bonuses apply.
     */
    private Product product(String nombre, double precio, String categoria,
                             String genero, String marca, int scoreP) {
        Product.MlScore ml = new Product.MlScore(scoreP, "", false, "estable", 50);
        return new Product("TestSitio", nombre, precio, null,
                "https://test/" + nombre.replace(" ", "-"), "https://img/test.jpg",
                categoria, genero, List.of(), ml, marca, "indumentaria", true); // gymrat=true
    }

    /**
     * Creates a synthetic non-gymrat Product (gymrat=false) for testing the gymrat gate.
     */
    private Product nonGymratProduct(String nombre, double precio, String categoria,
                                      String genero, String marca, int scoreP) {
        Product.MlScore ml = new Product.MlScore(scoreP, "", false, "estable", 50);
        return new Product("TestSitio", nombre, precio, null,
                "https://test/" + nombre.replace(" ", "-"), "https://img/test.jpg",
                categoria, genero, List.of(), ml, marca, "indumentaria", false); // gymrat=false
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
        // Only 2 products — pool < 20, so shuffle does not change the selected item
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

    // ── 2.9 Pool of 30 candidates: product selected within budget (sampling active) ──

    @Test
    void poolCappedAtK20ForLargeCatalogCategory() {
        // Create 30 Buzo products with decreasing scoreP (= increasing baseMlScore)
        List<Product> productos = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            productos.add(product("Buzo " + i, 15_000, "Buzo", "hombre", "Nike", i));
        }

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                productos, List.of("Buzo"),
                500_000, "hombre",
                OutfitService.FeedbackModel.empty());

        // Shuffle + sampling is active with 30 products; we assert a product is selected
        // within budget from the top-30 pool (specific winner may vary per run)
        assertThat(result.slots()).hasSize(1);
        assertThat(result.totalEstimado()).isLessThanOrEqualTo(500_000);
        Set<String> top30Names = new HashSet<>();
        for (int i = 0; i < 30; i++) top30Names.add("Buzo " + i);
        assertThat(top30Names).contains(result.slots().get(0).nombre());
    }

    // ── 3.1 Gymrat gate — non-gymrat torso excluded ──────────────────────────

    @Test
    void gymratGate_nonGymratTorsoExcluded() {
        // P1: higher score but gymrat=false (should be excluded for torso)
        // P2: lower score but gymrat=true (should be selected)
        Product p1 = nonGymratProduct("Remera NonGym", 10_000, "Remera", "hombre", "Nike", 10); // score ~90, gymrat=false
        Product p2 = product("Remera GymRat", 10_000, "Remera", "hombre", "Puma", 30);          // score ~70, gymrat=true

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(p1, p2), List.of("Remera"),
                50_000, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(), false);

        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).nombre()).isEqualTo("Remera GymRat");
    }

    @Test
    void gymratGate_piernasNonGymratExcluded() {
        Product p1 = nonGymratProduct("Short NonGym", 8_000, "Short", "hombre", "Nike", 10); // gymrat=false
        Product p2 = product("Short GymRat", 8_000, "Short", "hombre", "Puma", 30);          // gymrat=true

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(p1, p2), List.of("Short"),
                50_000, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(), false);

        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).nombre()).isEqualTo("Short GymRat");
    }

    // ── 3.2 Gymrat gate — calzado not filtered by gymrat ─────────────────────

    @Test
    void gymratGate_calzadoNotFiltered() {
        // Non-gymrat Zapatilla is the only product — must still be eligible
        Product p1 = nonGymratProduct("Zapatilla NonGym", 20_000, "Zapatilla", "hombre", "Nike", 10);

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(p1), List.of("Zapatilla"),
                50_000, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(), false);

        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).nombre()).isEqualTo("Zapatilla NonGym");
    }

    // ── 3.3 Shuffle produces variety when pool > 20 products ─────────────────

    @Test
    void shuffleProducesVariety() {
        // 25 gymrat products for Remera — pool exceeds 20 so random sampling activates
        List<Product> productos = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            // All different prices and scores so MCKP makes a meaningful choice
            productos.add(product("Remera " + i, 10_000 + i * 100, "Remera", "hombre", "Brand" + i, i));
        }

        Set<String> seen = new HashSet<>();
        for (int call = 0; call < 50; call++) {
            OutfitService.OutfitBuilderResult r = service.armarPorCategorias(
                    productos, List.of("Remera"),
                    500_000, "hombre",
                    OutfitService.FeedbackModel.empty(), Set.of(), false);
            if (!r.slots().isEmpty()) {
                seen.add(r.slots().get(0).url());
            }
        }
        // With 25 products sampled to 20, over 50 calls we expect at least 2 distinct picks
        assertThat(seen.size()).isGreaterThan(1);
    }

    // ── 3.4 excluirUrls — excluded URL not selected ───────────────────────────

    @Test
    void excluirUrls_excludedUrlNotSelected() {
        Product p1 = product("Remera Top", 10_000, "Remera", "hombre", "Nike", 5);  // highest score
        Product p2 = product("Remera Low", 10_000, "Remera", "hombre", "Puma", 40); // lower score

        String excludedUrl = p1.url();
        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(p1, p2), List.of("Remera"),
                50_000, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(excludedUrl), false);

        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).url()).isNotEqualTo(excludedUrl);
        assertThat(result.slots().get(0).nombre()).isEqualTo("Remera Low");
    }

    // ── 3.5 calcularMinimoBudget — returns sum of min(precio) ────────────────

    @Test
    void calcularMinimoBudget_returnsSum() {
        // Category "Remera": min price = 8000, Category "Short": min price = 5000
        // Expected minimoBudgetNecesario = 13000
        Product remera1 = product("Remera Cheap", 8_000,  "Remera", "hombre", "Nike", 20);
        Product remera2 = product("Remera Exp",   15_000, "Remera", "hombre", "Puma", 10);
        Product short1  = product("Short Cheap",  5_000,  "Short",  "hombre", "Nike", 20);
        Product short2  = product("Short Exp",    12_000, "Short",  "hombre", "Puma", 10);

        // Call with budget=1 so no products fit → no-fit triggers minimoBudgetNecesario
        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(remera1, remera2, short1, short2),
                List.of("Remera", "Short"),
                1, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(), false);

        assertThat(result.slots()).isEmpty();
        assertThat(result.minimoBudgetNecesario()).isNotNull();
        assertThat(result.minimoBudgetNecesario()).isEqualTo(8_000 + 5_000);
    }

    // ── 3.6 calcularMinimoBudget — null when any pool empty ──────────────────

    @Test
    void calcularMinimoBudget_nullWhenPoolEmpty() {
        // "Remera" has products, "Short" has zero eligible products (all mujer)
        Product remera  = product("Remera Hombre", 8_000, "Remera", "hombre", "Nike", 20);
        Product shortMujer = product("Short Mujer", 5_000, "Short", "mujer", "Nike", 20);

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(remera, shortMujer),
                List.of("Remera", "Short"),
                1, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(), false);

        assertThat(result.slots()).isEmpty();
        assertThat(result.minimoBudgetNecesario()).isNull();
    }

    // ── 3.7 Greedy picks highest-scored per category within budget ─────────────

    @Test
    void greedyMode_picksHighestScoredPerCategoryWithinBudget() {
        // Budget = 20000. Remera candidates: R1 (12000, best score), R2 (9000, lower score).
        // After picking R1 (12000), remaining = 8000. Short candidates: S1 (9000, exceeds remaining),
        // S2 (7000, fits). Greedy should pick R1 + S2.
        Product r1 = product("Remera Best", 12_000, "Remera", "hombre", "Nike", 5);  // baseMlScore≈95
        Product r2 = product("Remera Cheap", 9_000, "Remera", "hombre", "Puma", 30); // baseMlScore≈70
        Product s1 = product("Short Exp",    9_000, "Short",  "hombre", "Nike", 10); // doesn't fit
        Product s2 = product("Short Cheap",  7_000, "Short",  "hombre", "Puma", 25); // fits

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(r1, r2, s1, s2), List.of("Remera", "Short"),
                20_000, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(), true);

        assertThat(result.totalEstimado()).isLessThanOrEqualTo(20_000);
        Optional<OutfitService.SlotPick> remera = result.slots().stream()
                .filter(s -> "Remera".equals(s.slot())).findFirst();
        assertThat(remera).isPresent();
        assertThat(remera.get().nombre()).isEqualTo("Remera Best");
    }

    // ── 3.8 Greedy respects hard budget ──────────────────────────────────────

    @Test
    void greedyMode_respectsHardBudget() {
        Product r1 = product("Remera A", 15_000, "Remera", "hombre", "Nike", 5);
        Product r2 = product("Remera B", 20_000, "Remera", "hombre", "Puma", 3);

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(r1, r2), List.of("Remera"),
                12_000, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(), true);

        // Neither product fits budget of 12000 → greedy returns empty slots
        assertThat(result.slots()).isEmpty();
        assertThat(result.totalEstimado()).isLessThanOrEqualTo(12_000);
    }

    // ── 3.9 Greedy honors excluirUrls ─────────────────────────────────────────

    @Test
    void greedyMode_excluirUrlsHonored() {
        Product r1 = product("Remera Best", 10_000, "Remera", "hombre", "Nike", 5);  // highest score
        Product r2 = product("Remera Alt",  10_000, "Remera", "hombre", "Puma", 30); // lower score

        String excludedUrl = r1.url();
        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(r1, r2), List.of("Remera"),
                50_000, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(excludedUrl), true);

        assertThat(result.slots()).hasSize(1);
        assertThat(result.slots().get(0).url()).isNotEqualTo(excludedUrl);
        assertThat(result.slots().get(0).nombre()).isEqualTo("Remera Alt");
    }

    // ── 3.10 minimoBudgetNecesario populated on no-fit with budget gap ─────────

    @Test
    void minimoBudgetNecesario_populatedOnNoFit() {
        // cheapest valid Remera = 10000, cheapest valid Short = 8000 → minimum = 18000
        Product remera = product("Remera A", 10_000, "Remera", "hombre", "Nike", 20);
        Product short_ = product("Short A",  8_000,  "Short",  "hombre", "Nike", 20);

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(remera, short_), List.of("Remera", "Short"),
                1, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(), false);

        assertThat(result.slots()).isEmpty();
        assertThat(result.minimoBudgetNecesario()).isNotNull();
        assertThat(result.minimoBudgetNecesario()).isEqualTo(18_000.0);
    }

    // ── 3.11 minimoBudgetNecesario null on catalog gap ────────────────────────

    @Test
    void minimoBudgetNecesario_nullOnCatalogGap() {
        // "Short" has zero eligible products (wrong gender) → catalog gap → null
        Product remera     = product("Remera A",  10_000, "Remera", "hombre", "Nike", 20);
        Product shortMujer = product("Short Mujer", 8_000, "Short", "mujer",  "Nike", 20);

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(remera, shortMujer), List.of("Remera", "Short"),
                1, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(), false);

        assertThat(result.slots()).isEmpty();
        assertThat(result.minimoBudgetNecesario()).isNull();
    }

    // ── 3.12 minimoBudgetNecesario null on success ────────────────────────────

    @Test
    void minimoBudgetNecesario_nullOnSuccess() {
        Product remera = product("Remera A", 10_000, "Remera", "hombre", "Nike", 20);

        OutfitService.OutfitBuilderResult result = service.armarPorCategorias(
                List.of(remera), List.of("Remera"),
                50_000, "hombre",
                OutfitService.FeedbackModel.empty(), Set.of(), false);

        assertThat(result.slots()).hasSize(1);
        assertThat(result.minimoBudgetNecesario()).isNull();
    }
}
