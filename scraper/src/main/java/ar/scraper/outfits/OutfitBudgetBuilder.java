package ar.scraper.outfits;

import ar.scraper.model.Product;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** Budget-aware outfit builder: the MCKP solver and its greedy fallback. */
class OutfitBudgetBuilder {

    private final RecommendationService recommendationService;

    OutfitBudgetBuilder(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    private static final int BUILDER_POOL_K = 20;

    OutfitService.OutfitBuilderResult armarPorCategorias(
            List<Product> productos, List<String> categorias,
            double presupuesto, String genero, OutfitService.FeedbackModel feedback) {
        return armarPorCategorias(productos, categorias, presupuesto, genero, feedback,
                Set.of(), false);
    }

    /**
     * Assembles the globally-optimal product combination for the requested category set within a
     * hard budget ceiling using the Multi-Choice Knapsack Problem (MCKP) algorithm, or the greedy
     * fallback when {@code greedy=true}.
     */
    OutfitService.OutfitBuilderResult armarPorCategorias(
            List<Product> productos, List<String> categorias,
            double presupuesto, String genero, OutfitService.FeedbackModel feedback,
            Set<String> excluirUrls, boolean greedy) {
        return armarPorCategorias(productos, categorias, presupuesto, genero,
                feedback, excluirUrls, greedy, List.of());
    }

    /**
     * Like the 7-arg overload but accepts a list of products to lock into their resolved sub-slots
     * before the optimizer runs.
     */
    OutfitService.OutfitBuilderResult armarPorCategorias(
            List<Product> productos, List<String> categorias,
            double presupuesto, String genero, OutfitService.FeedbackModel feedback,
            Set<String> excluirUrls, boolean greedy, List<Product> pinned) {
        return armarPorCategorias(productos, categorias, presupuesto, genero,
                feedback, excluirUrls, greedy, pinned, "gym");
    }

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
        Map<String, Product> pinnedBySlot = new LinkedHashMap<>();
        for (Product pin : pinned) {
            if (pin == null) continue;
            if (excluirFinal.contains(pin.url())) continue;
            String subslot = OutfitService.CATEGORIA_SUBSLOT.get(pin.categoria());
            if (subslot == null) continue;
            if (!slotOrder.contains(subslot)) continue;
            if (pinnedBySlot.containsKey(subslot)) continue;
            pinnedBySlot.put(subslot, pin);
        }

        double pinnedTotal   = pinnedBySlot.values().stream().mapToDouble(Product::precio).sum();
        double reducedBudget = Math.max(0.0, presupuesto - pinnedTotal);

        List<String> openSlotOrder = slotOrder.stream()
                .filter(s -> !pinnedBySlot.containsKey(s))
                .collect(Collectors.toList());
        Map<String, Set<String>> openCatsBySlot = new LinkedHashMap<>();
        for (String s : openSlotOrder) openCatsBySlot.put(s, catsBySlot.get(s));

        if (openSlotOrder.isEmpty()) {
            List<OutfitService.SlotPick> picks = slotOrder.stream()
                    .filter(pinnedBySlot::containsKey)
                    .map(s -> OutfitRules.toSlotPick(s, pinnedBySlot.get(s)))
                    .collect(Collectors.toList());
            String g = genero != null ? genero : "";
            return new OutfitService.OutfitBuilderResult(picks, g, presupuesto, pinnedTotal,
                    false, List.of(), List.of(), null);
        }

        Map<String, List<Product>> pools =
                poolsPorSlot(productos, openSlotOrder, openCatsBySlot, genero, feedback,
                        excluirFinal, estilo);

        final Map<String, Integer> boostLikes = feedback.boostLikeCount();
        final double objetivoPorSlot = reducedBudget / openSlotOrder.size();

        if (greedy) {
            OutfitService.OutfitBuilderResult open =
                    armarGreedy(pools, openSlotOrder, reducedBudget, genero,
                                objetivoPorSlot, boostLikes);
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

            List<Scored> sortedRaw = puntuarYOrdenar(rawPool, objetivoPorSlot, boostLikes);

            // Take top-60 by score, shuffle to 30, filter by price — no re-sort after shuffle so
            // each regen sees a different candidate set (variety).
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
     * Merges pinned slot picks with the open-slot result, ordering by the original slot order so
     * pinned and freshly-chosen items interleave naturally.
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

    private record Scored(Product producto, double score) { }

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
    private List<Scored> puntuarYOrdenar(List<Product> pool, double objetivoPorSlot,
                                         Map<String, Integer> boostLikeCount) {
        double mitadBanda = Math.max(objetivoPorSlot * OutfitRules.PRICE_BAND_PCT, 1.0);
        List<Scored> scored = new ArrayList<>(pool.size());
        for (Product p : pool) {
            double s = OutfitRules.ML_SCORE_NEUTRO
                    * OutfitRules.mlFactor(recommendationService.baseMlScore(p))
                    * OutfitRules.boostFactor(p, boostLikeCount)
                    * OutfitRules.cercaniaDePrecio(p.precio(), objetivoPorSlot, mitadBanda);
            scored.add(new Scored(p, s));
        }
        scored.sort(Comparator.comparingDouble((Scored s) -> -s.score()));
        return scored;
    }

    /**
     * Greedy outfit assembler: for each category in order, picks the affordable candidate with the
     * highest contribution — its {@link #puntuarYOrdenar} score scaled by visual coherence and
     * brand diversity against what is already placed.
     */
    private OutfitService.OutfitBuilderResult armarGreedy(
            Map<String, List<Product>> pools, List<String> slotOrder, double presupuesto,
            String genero, double objetivoPorSlot, Map<String, Integer> boostLikeCount) {

        List<OutfitService.SlotPick> slots = new ArrayList<>();
        Map<String, Product> elegidos = new LinkedHashMap<>();
        double runningTotal  = 0.0;

        for (String slot : slotOrder) {
            List<Scored> sorted = puntuarYOrdenar(pools.get(slot), objetivoPorSlot, boostLikeCount);

            // Without this the greedy is deterministic and always returns the identical outfit.
            List<Scored> pool = new ArrayList<>(sorted.subList(0, Math.min(30, sorted.size())));
            Collections.shuffle(pool, ThreadLocalRandom.current());

            // Budget stays the hard constraint — coherence only reorders what already fits.
            final double remaining = presupuesto - runningTotal;
            Product mejor = null;
            double mejorAporte = -1.0;
            for (Scored s : pool) {
                if (s.producto().precio() > remaining) continue;
                double aporte = s.score()
                        * VisualCoherence.coherencia(slot, s.producto(), elegidos)
                        * OutfitRules.diversidadDeMarca(s.producto(), elegidos);
                if (aporte > mejorAporte) {
                    mejor = s.producto();
                    mejorAporte = aporte;
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

    private Double calcularMinimoBudget(Map<String, List<Product>> pools, List<String> slotOrder) {
        double total = 0.0;
        for (String slot : slotOrder) {
            List<Product> pool = pools.get(slot);
            if (pool.isEmpty()) {
                return null;
            }
            double min = Double.POSITIVE_INFINITY;
            for (Product p : pool) min = Math.min(min, p.precio());
            total += min;
        }
        return total;
    }

    /** If this upper bound cannot beat the current best solution, the branch is pruned. */
    private static final class MckpSolver {
        private final List<List<Scored>> pools;
        private final List<String>       slotOrder;
        private final double             presupuesto;
        private final double[]           maxScorePerCat;
        /** Suffix sums of maxScorePerCat, so the upper bound is a lookup, not a loop. */
        private final double[]           maxScoreDesde;

        /**
         * The partial assignment, mutated in step with the recursion instead of rebuilt per node —
         * this map is read on every candidate of every branch, so allocating one would undo the
         * point of caching the scores.
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

            if (score + maxScoreDesde[idx] <= bestScore) return;

            current[idx] = null;
            solve(idx + 1, total, score, current);

            double remaining = presupuesto - total;
            String slot = slotOrder.get(idx);
            for (Scored s : pools.get(idx)) {
                if (s.producto().precio() > remaining) continue;
                // Scored BEFORE the candidate joins the partial assignment — it must not be
                // compared against itself.
                double aporte = aporte(s, slot);
                current[idx] = s;
                elegidos.put(slot, s.producto());
                solve(idx + 1, total + s.producto().precio(), score + aporte, current);
                elegidos.remove(slot);
            }
            current[idx] = null;
        }

        /**
         * Any future term added here must keep that property: a factor that could exceed 1.0 would
         * silently start pruning the optimum.
         */
        private double aporte(Scored s, String slot) {
            double factor = VisualCoherence.coherencia(slot, s.producto(), elegidos)
                    * OutfitRules.diversidadDeMarca(s.producto(), elegidos);
            return s.score() - Math.max(0.0, s.score()) * (1.0 - factor);
        }
    }
}
