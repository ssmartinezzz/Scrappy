package ar.scraper.web;

import ar.scraper.model.Product;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Budget-aware outfit builder: the MCKP solver and its greedy fallback.
 *
 * <p>Extracted from {@link OutfitService} (backlog A3). Unlike the other slices
 * this one was NOT self-contained — it shared {@code generoElegible},
 * {@code pasaEstiloGate} and {@code toSlotPick} with the random assembler, so
 * those three moved to {@link OutfitRules} where both callers can reach them
 * rather than being duplicated or reached through a back-reference.</p>
 *
 * <p>{@code CATEGORIA_SUBSLOT} stays declared on OutfitService because
 * {@code OutfitServiceSubslotTest} reads that field by reflection off
 * {@code OutfitService.class}.</p>
 */
class OutfitBudgetBuilder {

    private final RecommendationService recommendationService;

    OutfitBudgetBuilder(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    // ─── Budget Builder (MCKP) ───────────────────────────────────────────────────

    /** Maximum candidates considered per category during MCKP enumeration. */
    private static final int BUILDER_POOL_K = 20;

    /**
     * Backward-compatible 5-arg overload. Delegates to the 7-arg implementation
     * with no exclusions and MCKP mode. Keeps all existing callers and tests unchanged.
     */
    OutfitService.OutfitBuilderResult armarPorCategorias(
            List<Product> productos, List<String> categorias,
            double presupuesto, String genero, OutfitService.FeedbackModel feedback) {
        return armarPorCategorias(productos, categorias, presupuesto, genero, feedback,
                Set.of(), false);
    }

    /**
     * Assembles the globally-optimal product combination for the requested
     * category set within a hard budget ceiling using the Multi-Choice
     * Knapsack Problem (MCKP) algorithm, or the greedy fallback when
     * {@code greedy=true}.
     *
     * <p>Algorithm phases (MCKP):
     * <ol>
     *   <li>Build per-category raw pools: filter by categoria, gender, feedback
     *       exclusions, gymrat gate (torso/piernas only), and excluirUrls.</li>
     *   <li>Shuffle for variety: sort desc, take top-30, random-sample 20,
     *       re-sort desc (INVARIANT: pool.get(0) must be max score for B&B).</li>
     *   <li>Apply price filter (≤ presupuesto), cap at K=20.</li>
     *   <li>Recursive branch-and-bound enumeration.</li>
     *   <li>Build result; on no-fit, compute minimoBudgetNecesario.</li>
     * </ol>
     *
     * <p>INVARIANT: {@code result.totalEstimado() ≤ presupuesto} always holds.
     *
     * @param productos    in-memory catalog (from {@code ScraperService.lastResult})
     * @param categorias   requested canonical category names (deduplicated, ordered)
     * @param presupuesto  hard budget ceiling (must be &gt; 0)
     * @param genero       optional gender filter; null/blank = no filter
     * @param feedback     veto/boost model; null treated as empty
     * @param excluirUrls  URLs to exclude per-request (temp, not persisted)
     * @param greedy       when true, use greedy (best-per-category) instead of MCKP
     * @return optimal assignment or no-fit result
     */
    OutfitService.OutfitBuilderResult armarPorCategorias(
            List<Product> productos, List<String> categorias,
            double presupuesto, String genero, OutfitService.FeedbackModel feedback,
            Set<String> excluirUrls, boolean greedy) {
        return armarPorCategorias(productos, categorias, presupuesto, genero,
                feedback, excluirUrls, greedy, List.of());
    }

    /**
     * Pin-aware outfit assembler. Like the 7-arg overload but accepts a list of
     * products to lock into their resolved sub-slots before the optimizer runs.
     * Pinned products are excluded from the MCKP/greedy search; the remaining
     * budget ({@code presupuesto - Σ pinned.precio}, floored at 0) is used for
     * the open slots. Pinned products that cannot be resolved (unknown category,
     * sub-slot not in the requested set, URL in {@code excluirUrls}) are silently
     * dropped and their slot is treated as open.
     */
    OutfitService.OutfitBuilderResult armarPorCategorias(
            List<Product> productos, List<String> categorias,
            double presupuesto, String genero, OutfitService.FeedbackModel feedback,
            Set<String> excluirUrls, boolean greedy, List<Product> pinned) {
        return armarPorCategorias(productos, categorias, presupuesto, genero,
                feedback, excluirUrls, greedy, pinned, "gym");
    }

    /**
     * Style-aware outfit assembler. Like the 8-arg overload but accepts the active
     * {@code estilo} ("gym" | "casual"), which selects the torso/piernas eligibility
     * gate via {@link #pasaEstiloGate}. Calzado and accesorio are unaffected by estilo
     * (category-driven eligibility). All other behavior (MCKP/greedy, budget invariant,
     * pinning, feedback vetoes) is identical to the 8-arg path.
     */
    OutfitService.OutfitBuilderResult armarPorCategorias(
            List<Product> productos, List<String> categorias,
            double presupuesto, String genero, OutfitService.FeedbackModel feedback,
            Set<String> excluirUrls, boolean greedy, List<Product> pinned, String estilo) {
        if (productos == null) productos = List.of();
        if (feedback == null) feedback = OutfitService.FeedbackModel.empty();
        if (excluirUrls == null) excluirUrls = Set.of();
        if (pinned == null) pinned = List.of();
        if (categorias == null || categorias.isEmpty()) {
            return new OutfitService.OutfitBuilderResult(List.of(), genero != null ? genero : "",
                    presupuesto, 0.0, false, List.of(), List.of(), null);
        }

        // Deduplicate; resolve each category to its sub-slot key via OutfitService.CATEGORIA_SUBSLOT.
        // torso-base / torso-outer are independent picks (layering); piernas and calzado
        // group all selected categories into one pick; accesorio splits into head/feet/body.
        List<String> cats = new ArrayList<>(new LinkedHashSet<>(categorias));
        final Set<String> excluirFinal = excluirUrls;

        Map<String, Set<String>> catsBySlot = new LinkedHashMap<>();
        for (String cat : cats) {
            String subslot = OutfitService.CATEGORIA_SUBSLOT.get(cat);
            if (subslot == null) continue;
            catsBySlot.computeIfAbsent(subslot, k -> new LinkedHashSet<>()).add(cat);
        }
        List<String> slotOrder = new ArrayList<>(catsBySlot.keySet());

        // Pin pre-processing — lock requested products into their sub-slots.
        // Rules: exclude wins over pin; unknown categories and out-of-scope sub-slots
        // are skipped; first pin wins on duplicate sub-slot collisions.
        Map<String, Product> pinnedBySlot = new LinkedHashMap<>();
        for (Product pin : pinned) {
            if (pin == null) continue;
            if (excluirFinal.contains(pin.url())) continue;
            String subslot = OutfitService.CATEGORIA_SUBSLOT.get(pin.categoria());
            if (subslot == null) continue;
            if (!slotOrder.contains(subslot)) continue;
            if (pinnedBySlot.containsKey(subslot)) continue; // first-wins on collision
            pinnedBySlot.put(subslot, pin);
        }

        double pinnedTotal   = pinnedBySlot.values().stream().mapToDouble(Product::precio).sum();
        double reducedBudget = Math.max(0.0, presupuesto - pinnedTotal);

        List<String> openSlotOrder = slotOrder.stream()
                .filter(s -> !pinnedBySlot.containsKey(s))
                .collect(Collectors.toList());
        Map<String, Set<String>> openCatsBySlot = new LinkedHashMap<>();
        for (String s : openSlotOrder) openCatsBySlot.put(s, catsBySlot.get(s));

        // All-pinned short-circuit: no open slots to optimize — return immediately.
        if (openSlotOrder.isEmpty()) {
            List<OutfitService.SlotPick> picks = slotOrder.stream()
                    .filter(pinnedBySlot::containsKey)
                    .map(s -> OutfitRules.toSlotPick(s, pinnedBySlot.get(s)))
                    .collect(Collectors.toList());
            String g = genero != null ? genero : "";
            return new OutfitService.OutfitBuilderResult(picks, g, presupuesto, pinnedTotal,
                    false, List.of(), List.of(), null);
        }

        if (greedy) {
            OutfitService.OutfitBuilderResult open = armarGreedy(productos, openSlotOrder, openCatsBySlot,
                    reducedBudget, genero, feedback, excluirFinal, estilo);
            return mergePinned(open, pinnedBySlot, slotOrder, presupuesto);
        }

        Set<String> exclude          = feedback.exclude();
        Set<String> excludeCategoria = feedback.excludeCategoria();

        List<String>        slotsVacios = new ArrayList<>();
        List<List<Product>> allPools    = new ArrayList<>();
        List<Boolean>       rawNonEmpty = new ArrayList<>();

        for (String slot : openSlotOrder) {
            Set<String> slotCats = openCatsBySlot.get(slot);
            List<Product> rawPool = productos.stream()
                    .filter(p -> slotCats.contains(p.categoria()))
                    .filter(p -> OutfitRules.generoElegible(p, genero))
                    .filter(p -> !exclude.contains(OutfitService.FeedbackModel.keyOf(p)))
                    .filter(p -> p.categoria() == null || !excludeCategoria.contains(p.categoria()))
                    .filter(p -> !excluirFinal.contains(p.url()))
                    .filter(p -> OutfitRules.pasaEstiloGate(p, slot, estilo))
                    .collect(Collectors.toList());

            if (rawPool.isEmpty()) {
                slotsVacios.addAll(openCatsBySlot.get(slot));
                allPools.add(List.of());
                rawNonEmpty.add(false);
                continue;
            }

            rawNonEmpty.add(true);

            List<Product> sortedRaw = rawPool.stream()
                    .sorted(Comparator.comparingDouble((Product p) -> -recommendationService.baseMlScore(p)))
                    .collect(Collectors.toList());

            // Take top-60 by score, shuffle to 30, filter by price — no re-sort after
            // shuffle so each regen sees a different candidate set (variety).
            List<Product> top60 = new ArrayList<>(sortedRaw.subList(0, Math.min(60, sortedRaw.size())));
            Collections.shuffle(top60, new Random());
            List<Product> filteredPool = top60.stream()
                    .filter(p -> p.precio() <= reducedBudget)
                    .limit(BUILDER_POOL_K)
                    .collect(Collectors.toList());

            allPools.add(filteredPool);
        }

        MckpSolver solver = new MckpSolver(recommendationService, allPools, reducedBudget);
        solver.solve(0, 0.0, 0.0, new Product[openSlotOrder.size()]);

        Product[] bestSolution = solver.best;
        Set<String> slotsInSolution = new HashSet<>();
        List<OutfitService.SlotPick> slots = new ArrayList<>();

        for (int i = 0; i < openSlotOrder.size(); i++) {
            Product p = bestSolution[i];
            if (p != null) {
                slots.add(OutfitRules.toSlotPick(openSlotOrder.get(i), p));
                slotsInSolution.add(openSlotOrder.get(i));
            }
        }

        List<String> slotsSinPresupuesto = new ArrayList<>();
        for (int i = 0; i < openSlotOrder.size(); i++) {
            String slot = openSlotOrder.get(i);
            if (rawNonEmpty.get(i) && !slotsInSolution.contains(slot)) {
                slotsSinPresupuesto.addAll(openCatsBySlot.get(slot));
            }
        }

        boolean noCumplePresupuesto = !slotsSinPresupuesto.isEmpty();
        double totalEstimado = slots.stream().mapToDouble(OutfitService.SlotPick::precio).sum();
        String generoResultado = genero != null ? genero : "";

        Double minimoBudgetNecesario = null;
        if (slots.isEmpty()) {
            minimoBudgetNecesario = calcularMinimoBudget(
                    productos, openSlotOrder, openCatsBySlot, genero, feedback, excluirFinal, estilo);
        }

        OutfitService.OutfitBuilderResult open = new OutfitService.OutfitBuilderResult(slots, generoResultado, reducedBudget,
                totalEstimado, noCumplePresupuesto, slotsVacios, slotsSinPresupuesto,
                minimoBudgetNecesario);
        return mergePinned(open, pinnedBySlot, slotOrder, presupuesto);
    }

    /**
     * Merges pinned slot picks with the open-slot result, ordering by the original
     * slot order so pinned and freshly-chosen items interleave naturally.
     * Reports the original (unreduced) budget ceiling and adds pinned prices to
     * the open total. Diagnostic fields ({@code categoriasVacias},
     * {@code categoriasSinPresupuesto}, {@code minimoBudgetNecesario},
     * {@code noCumplePresupuesto}) are taken from the open result only —
     * pinned slots are satisfied by definition.
     */
    private OutfitService.OutfitBuilderResult mergePinned(
            OutfitService.OutfitBuilderResult open, Map<String, Product> pinnedBySlot,
            List<String> originalSlotOrder, double presupuestoOriginal) {
        Map<String, OutfitService.SlotPick> bySlot = new LinkedHashMap<>();
        for (OutfitService.SlotPick sp : open.slots()) bySlot.put(sp.slot(), sp);
        for (Map.Entry<String, Product> e : pinnedBySlot.entrySet()) {
            bySlot.put(e.getKey(), OutfitRules.toSlotPick(e.getKey(), e.getValue()));
        }

        List<OutfitService.SlotPick> merged = originalSlotOrder.stream()
                .filter(bySlot::containsKey)
                .map(bySlot::get)
                .collect(Collectors.toList());

        double pinnedTotal   = pinnedBySlot.values().stream().mapToDouble(Product::precio).sum();
        double totalEstimado = open.totalEstimado() + pinnedTotal;

        return new OutfitService.OutfitBuilderResult(
                merged,
                open.genero(),
                presupuestoOriginal,
                totalEstimado,
                open.noCumplePresupuesto(),
                open.categoriasVacias(),
                open.categoriasSinPresupuesto(),
                open.minimoBudgetNecesario());
    }

    /**
     * Greedy outfit assembler: for each category in order, picks the highest
     * baseMlScore candidate where {@code precio ≤ remainingBudget}. Hard budget
     * is always enforced (never exceeded). Categories with no affordable candidate
     * are skipped.
     *
     * <p>Applies the same gymrat gate as the MCKP path so all three paths
     * (MCKP, greedy, calcularMinimoBudget) use identical eligibility rules.
     */
    private OutfitService.OutfitBuilderResult armarGreedy(
            List<Product> productos, List<String> slotOrder,
            Map<String, Set<String>> catsBySlot, double presupuesto,
            String genero, OutfitService.FeedbackModel feedback, Set<String> excluirUrls, String estilo) {
        if (productos == null) productos = List.of();

        Set<String> exclude          = feedback.exclude();
        Set<String> excludeCategoria = feedback.excludeCategoria();

        List<OutfitService.SlotPick> slots = new ArrayList<>();
        double runningTotal  = 0.0;

        for (String slot : slotOrder) {
            Set<String> slotCats = catsBySlot.get(slot);
            List<Product> sorted = productos.stream()
                    .filter(p -> slotCats.contains(p.categoria()))
                    .filter(p -> OutfitRules.generoElegible(p, genero))
                    .filter(p -> !exclude.contains(OutfitService.FeedbackModel.keyOf(p)))
                    .filter(p -> p.categoria() == null || !excludeCategoria.contains(p.categoria()))
                    .filter(p -> !excluirUrls.contains(p.url()))
                    .filter(p -> OutfitRules.pasaEstiloGate(p, slot, estilo))
                    .sorted(Comparator.comparingDouble((Product p) -> -recommendationService.baseMlScore(p)))
                    .collect(Collectors.toList());

            // Shuffle top-30 by score for variety across re-rolls (same pattern as MCKP pool).
            // Without this the greedy is deterministic and always returns the identical outfit.
            List<Product> pool = new ArrayList<>(sorted.subList(0, Math.min(30, sorted.size())));
            Collections.shuffle(pool, new Random());

            final double remaining = presupuesto - runningTotal;
            Optional<Product> pick = pool.stream()
                    .filter(p -> p.precio() <= remaining)
                    .findFirst();

            if (pick.isPresent()) {
                Product chosen = pick.get();
                slots.add(OutfitRules.toSlotPick(slot, chosen));
                runningTotal += chosen.precio();
            }
        }

        String generoResultado = genero != null ? genero : "";
        double totalEstimado   = slots.stream().mapToDouble(OutfitService.SlotPick::precio).sum();
        return new OutfitService.OutfitBuilderResult(slots, generoResultado, presupuesto,
                totalEstimado, false, List.of(), List.of(), null);
    }

    /**
     * Returns the minimum budget needed to assemble one product per category,
     * using the same eligibility filters as the MCKP pool (gymrat gate, gender,
     * feedback, excluirUrls) but ignoring price. Returns null if any category
     * has zero eligible products (catalog gap).
     *
     * <p>Used to populate {@link OutfitService.OutfitBuilderResult#minimoBudgetNecesario()} on
     * no-fit responses so the frontend can show "Necesitás al menos $X más".
     */
    private Double calcularMinimoBudget(
            List<Product> productos, List<String> slotOrder,
            Map<String, Set<String>> catsBySlot, String genero,
            OutfitService.FeedbackModel feedback, Set<String> excluirUrls, String estilo) {
        if (productos == null) return null;

        Set<String> exclude          = feedback.exclude();
        Set<String> excludeCategoria = feedback.excludeCategoria();

        double total = 0.0;
        for (String slot : slotOrder) {
            Set<String> slotCats = catsBySlot.get(slot);
            OptionalDouble minPrecio = productos.stream()
                    .filter(p -> slotCats.contains(p.categoria()))
                    .filter(p -> OutfitRules.generoElegible(p, genero))
                    .filter(p -> !exclude.contains(OutfitService.FeedbackModel.keyOf(p)))
                    .filter(p -> p.categoria() == null || !excludeCategoria.contains(p.categoria()))
                    .filter(p -> !excluirUrls.contains(p.url()))
                    .filter(p -> OutfitRules.pasaEstiloGate(p, slot, estilo))
                    .mapToDouble(Product::precio)
                    .min();

            if (minPrecio.isEmpty()) {
                return null; // catalog gap — no eligible product for this slot
            }
            total += minPrecio.getAsDouble();
        }
        return total;
    }

    /**
     * Multi-Choice Knapsack Problem solver.
     * One item is chosen from each category group (or the group is skipped),
     * subject to {@code sum(prices) ≤ presupuesto}. Maximizes total
     * {@code baseMlScore} across all selected items.
     *
     * <p>Branch-and-bound pruning: at each node, the upper bound is the
     * current running score plus the sum of the best (index-0) score for
     * each remaining category pool. If this upper bound cannot beat the
     * current best solution, the branch is pruned.
     */
    private static final class MckpSolver {
        private final RecommendationService recService;
        private final List<List<Product>>   pools;
        private final double                presupuesto;
        private final double[]              maxScorePerCat;

        Product[] best;
        double    bestScore = Double.NEGATIVE_INFINITY;

        MckpSolver(RecommendationService recService,
                   List<List<Product>> pools, double presupuesto) {
            this.recService  = recService;
            this.pools       = pools;
            this.presupuesto = presupuesto;
            int n = pools.size();
            this.best           = new Product[n];
            this.maxScorePerCat = new double[n];
            for (int i = 0; i < n; i++) {
                List<Product> pool = pools.get(i);
                // Pool is sorted desc by baseMlScore; first element is the max
                maxScorePerCat[i] = pool.isEmpty()
                        ? 0.0 : pool.stream()
                            .mapToDouble(recService::baseMlScore)
                            .max().orElse(0.0);
            }
        }

        void solve(int idx, double total, double score, Product[] current) {
            if (idx == pools.size()) {
                if (score > bestScore) {
                    bestScore = score;
                    System.arraycopy(current, 0, best, 0, current.length);
                }
                return;
            }

            // Branch-and-bound: if max possible score from here ≤ bestScore, prune
            double upperBound = score;
            for (int i = idx; i < pools.size(); i++) upperBound += maxScorePerCat[i];
            if (upperBound <= bestScore) return;

            // Option A: skip this category (partial outfit)
            current[idx] = null;
            solve(idx + 1, total, score, current);

            // Option B: pick an affordable candidate
            double remaining = presupuesto - total;
            for (Product p : pools.get(idx)) {
                if (p.precio() > remaining) continue;
                current[idx] = p;
                solve(idx + 1, total + p.precio(),
                      score + recService.baseMlScore(p), current);
            }
            current[idx] = null; // backtrack
        }
    }
}
