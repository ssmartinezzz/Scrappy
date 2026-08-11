package ar.scraper.web;

import ar.scraper.model.Product;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
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
     *   <li>Build per-sub-slot raw pools in ONE catalog pass ({@link #poolsPorSlot}):
     *       filter by categoria, gender, feedback exclusions, style gate
     *       (torso/piernas only), and excluirUrls.</li>
     *   <li>Score each candidate once, sort desc, take the top 60 and shuffle them
     *       so each regen sees a different candidate set (variety).</li>
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
     * gate via {@link OutfitRules#pasaEstiloGate}. Calzado and accesorio are unaffected by estilo
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

        // ONE pass over the catalog for all open sub-slots. The three consumers
        // (MCKP, greedy, calcularMinimoBudget) apply identical eligibility rules,
        // and each of them used to re-scan the whole catalog once per sub-slot —
        // up to seven full scans per path, three paths, on every regen click.
        Map<String, List<Product>> pools =
                poolsPorSlot(productos, openSlotOrder, openCatsBySlot, genero, feedback,
                        excluirFinal, estilo);

        if (greedy) {
            OutfitService.OutfitBuilderResult open =
                    armarGreedy(pools, openSlotOrder, reducedBudget, genero);
            return mergePinned(open, pinnedBySlot, slotOrder, presupuesto);
        }

        List<String>       slotsVacios = new ArrayList<>();
        List<List<Scored>> allPools    = new ArrayList<>();
        List<Boolean>      rawNonEmpty = new ArrayList<>();

        for (String slot : openSlotOrder) {
            List<Product> rawPool = pools.get(slot);

            if (rawPool.isEmpty()) {
                slotsVacios.addAll(openCatsBySlot.get(slot));
                allPools.add(List.of());
                rawNonEmpty.add(false);
                continue;
            }

            rawNonEmpty.add(true);

            List<Scored> sortedRaw = puntuarYOrdenar(rawPool);

            // Take top-60 by score, shuffle to 30, filter by price — no re-sort after
            // shuffle so each regen sees a different candidate set (variety).
            List<Scored> top60 = new ArrayList<>(sortedRaw.subList(0, Math.min(60, sortedRaw.size())));
            Collections.shuffle(top60, ThreadLocalRandom.current());
            List<Scored> filteredPool = new ArrayList<>(BUILDER_POOL_K);
            for (Scored s : top60) {
                if (s.producto().precio() > reducedBudget) continue;
                filteredPool.add(s);
                if (filteredPool.size() == BUILDER_POOL_K) break;
            }

            allPools.add(filteredPool);
        }

        MckpSolver solver = new MckpSolver(allPools, openSlotOrder, reducedBudget);
        solver.solve(0, 0.0, 0.0, new Scored[openSlotOrder.size()]);

        Scored[] bestSolution = solver.best;
        Set<String> slotsInSolution = new HashSet<>();
        List<OutfitService.SlotPick> slots = new ArrayList<>();

        for (int i = 0; i < openSlotOrder.size(); i++) {
            Scored s = bestSolution[i];
            if (s != null) {
                slots.add(OutfitRules.toSlotPick(openSlotOrder.get(i), s.producto()));
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
            minimoBudgetNecesario = calcularMinimoBudget(pools, openSlotOrder);
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
     * A candidate with its {@code baseMlScore} already computed.
     *
     * <p>The score used to be recomputed inside a sort comparator (O(n log n)
     * evaluations per sub-slot), then again to seed the branch-and-bound upper
     * bound, then once more at every node of the recursion. It is a pure function
     * of the product, so computing it once per candidate and carrying it is both
     * cheaper and impossible to get inconsistent.</p>
     */
    private record Scored(Product producto, double score) { }

    /**
     * Partitions the catalog into per-sub-slot candidate pools in a single pass.
     *
     * <p>A product's categoria resolves to exactly one sub-slot, so one pass yields
     * the same lists — in the same catalog order — that a per-sub-slot stream did,
     * without walking the catalog again for every slot. The eligibility rules are
     * applied here once and shared by all three paths (MCKP, greedy,
     * {@link #calcularMinimoBudget}), which is also what keeps them from drifting
     * apart: they used to be three hand-copied filter chains.</p>
     */
    private Map<String, List<Product>> poolsPorSlot(
            List<Product> productos, List<String> slotOrder,
            Map<String, Set<String>> catsBySlot, String genero,
            OutfitService.FeedbackModel feedback, Set<String> excluirUrls, String estilo) {

        Set<String> exclude          = feedback.exclude();
        Set<String> excludeCategoria = feedback.excludeCategoria();

        Map<String, String> slotDeCategoria = new HashMap<>();
        Map<String, List<Product>> pools = new LinkedHashMap<>();
        for (String slot : slotOrder) {
            pools.put(slot, new ArrayList<>());
            for (String cat : catsBySlot.get(slot)) slotDeCategoria.put(cat, slot);
        }

        for (Product p : productos) {
            // A null categoria simply never resolves — same outcome as the old
            // slotCats.contains(null), which was always false.
            String slot = slotDeCategoria.get(p.categoria());
            if (slot == null) continue;
            if (!OutfitRules.generoElegible(p, genero)) continue;
            if (exclude.contains(OutfitService.FeedbackModel.keyOf(p))) continue;
            if (excludeCategoria.contains(p.categoria())) continue;
            if (excluirUrls.contains(p.url())) continue;
            if (!OutfitRules.pasaEstiloGate(p, slot, estilo)) continue;
            pools.get(slot).add(p);
        }
        return pools;
    }

    /** Scores each candidate once, then sorts descending — stable, so ties keep catalog order. */
    private List<Scored> puntuarYOrdenar(List<Product> pool) {
        List<Scored> scored = new ArrayList<>(pool.size());
        for (Product p : pool) scored.add(new Scored(p, recommendationService.baseMlScore(p)));
        scored.sort(Comparator.comparingDouble((Scored s) -> -s.score()));
        return scored;
    }

    /**
     * Greedy outfit assembler: for each category in order, picks the highest
     * baseMlScore candidate where {@code precio ≤ remainingBudget}. Hard budget
     * is always enforced (never exceeded). Categories with no affordable candidate
     * are skipped.
     *
     * <p>Reads the pools built by {@link #poolsPorSlot}, so it applies exactly the
     * same eligibility rules as the MCKP path rather than a second copy of them.
     */
    private OutfitService.OutfitBuilderResult armarGreedy(
            Map<String, List<Product>> pools, List<String> slotOrder, double presupuesto,
            String genero) {

        List<OutfitService.SlotPick> slots = new ArrayList<>();
        Map<String, Product> elegidos = new LinkedHashMap<>();
        double runningTotal  = 0.0;

        for (String slot : slotOrder) {
            List<Scored> sorted = puntuarYOrdenar(pools.get(slot));

            // Shuffle top-30 by score for variety across re-rolls (same pattern as MCKP pool).
            // Without this the greedy is deterministic and always returns the identical outfit.
            List<Scored> pool = new ArrayList<>(sorted.subList(0, Math.min(30, sorted.size())));
            Collections.shuffle(pool, ThreadLocalRandom.current());

            // Among the affordable candidates, the most visually coherent one; ties go
            // to the earliest in the shuffled pool, which is exactly the "first
            // affordable" rule this used to be. Budget stays the hard constraint —
            // coherence only reorders what already fits.
            final double remaining = presupuesto - runningTotal;
            Product mejor = null;
            double mejorCoherencia = -1.0;
            for (Scored s : pool) {
                if (s.producto().precio() > remaining) continue;
                double coh = VisualCoherence.coherencia(slot, s.producto(), elegidos);
                if (coh > mejorCoherencia) {
                    mejor = s.producto();
                    mejorCoherencia = coh;
                    if (coh >= 1.0) break; // nothing can beat a fully coherent candidate
                }
            }

            if (mejor != null) {
                slots.add(OutfitRules.toSlotPick(slot, mejor));
                elegidos.put(slot, mejor);
                runningTotal += mejor.precio();
            }
        }

        String generoResultado = genero != null ? genero : "";
        double totalEstimado   = slots.stream().mapToDouble(OutfitService.SlotPick::precio).sum();
        return new OutfitService.OutfitBuilderResult(slots, generoResultado, presupuesto,
                totalEstimado, false, List.of(), List.of(), null);
    }

    /**
     * Returns the minimum budget needed to assemble one product per category,
     * reading the pools already built by {@link #poolsPorSlot} and ignoring price.
     * Returns null if any category has zero eligible products (catalog gap).
     *
     * <p>Used to populate {@link OutfitService.OutfitBuilderResult#minimoBudgetNecesario()} on
     * no-fit responses so the frontend can show "Necesitás al menos $X más".
     */
    private Double calcularMinimoBudget(Map<String, List<Product>> pools, List<String> slotOrder) {
        double total = 0.0;
        for (String slot : slotOrder) {
            List<Product> pool = pools.get(slot);
            if (pool.isEmpty()) {
                return null; // catalog gap — no eligible product for this slot
            }
            double min = Double.POSITIVE_INFINITY;
            for (Product p : pool) min = Math.min(min, p.precio());
            total += min;
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
        private final List<List<Scored>> pools;
        private final List<String>       slotOrder;
        private final double             presupuesto;
        private final double[]           maxScorePerCat;
        /** Suffix sums of maxScorePerCat, so the upper bound is a lookup, not a loop. */
        private final double[]           maxScoreDesde;

        /**
         * The partial assignment, mutated in step with the recursion instead of
         * rebuilt per node — this map is read on every candidate of every branch,
         * so allocating one would undo the point of caching the scores.
         */
        private final Map<String, Product> elegidos = new LinkedHashMap<>();

        Scored[] best;
        double   bestScore = Double.NEGATIVE_INFINITY;

        MckpSolver(List<List<Scored>> pools, List<String> slotOrder, double presupuesto) {
            this.pools       = pools;
            this.slotOrder   = slotOrder;
            this.presupuesto = presupuesto;
            int n = pools.size();
            this.best           = new Scored[n];
            this.maxScorePerCat = new double[n];
            this.maxScoreDesde  = new double[n + 1];
            for (int i = 0; i < n; i++) {
                double max = 0.0;
                for (Scored s : pools.get(i)) max = Math.max(max, s.score());
                maxScorePerCat[i] = max;
            }
            for (int i = n - 1; i >= 0; i--) {
                maxScoreDesde[i] = maxScoreDesde[i + 1] + maxScorePerCat[i];
            }
        }

        void solve(int idx, double total, double score, Scored[] current) {
            if (idx == pools.size()) {
                if (score > bestScore) {
                    bestScore = score;
                    System.arraycopy(current, 0, best, 0, current.length);
                }
                return;
            }

            // Branch-and-bound: if max possible score from here ≤ bestScore, prune
            if (score + maxScoreDesde[idx] <= bestScore) return;

            // Option A: skip this category (partial outfit)
            current[idx] = null;
            solve(idx + 1, total, score, current);

            // Option B: pick an affordable candidate
            double remaining = presupuesto - total;
            String slot = slotOrder.get(idx);
            for (Scored s : pools.get(idx)) {
                if (s.producto().precio() > remaining) continue;
                // Scored BEFORE the candidate joins the partial assignment — it must
                // not be compared against itself.
                double aporte = aporte(s, slot);
                current[idx] = s;
                elegidos.put(slot, s.producto());
                solve(idx + 1, total + s.producto().precio(), score + aporte, current);
                elegidos.remove(slot);
            }
            current[idx] = null; // backtrack
        }

        /**
         * A candidate's contribution to the objective: its ML score, minus a visual
         * incoherence penalty against the slots already assigned on this branch.
         *
         * <p>The penalty is a SUBTRACTION of a non-negative amount, which is what keeps
         * the branch-and-bound sound: {@code aporte ≤ s.score()} always, so
         * {@code maxScoreDesde} — built from unpenalized scores — remains a valid upper
         * bound and no optimal branch is ever pruned.</p>
         *
         * <p>Each unordered pair of slots is evaluated exactly once, when the later of
         * the two is assigned, so the total is independent of the order the solver
         * happens to walk the slots in.</p>
         */
        private double aporte(Scored s, String slot) {
            double coherencia = VisualCoherence.coherencia(slot, s.producto(), elegidos);
            return s.score() - Math.max(0.0, s.score()) * (1.0 - coherencia);
        }
    }
}
