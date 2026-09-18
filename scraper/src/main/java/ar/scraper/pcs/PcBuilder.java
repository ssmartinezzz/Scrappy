package ar.scraper.pcs;

import ar.scraper.model.Product;
import ar.scraper.outfits.RecommendationService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Assembles a PC from the in-memory catalog: one pick per slot, best-effort,
 * with hard compatibility vetoes wherever both sides of a rule parsed. Mirrors
 * {@code SupplementCombo}'s pick/budget/exclude shape, minus the brand tiers —
 * hardware has no analogue for those yet. See odd/tasks/pc-builder.md for the
 * design (slots, vetoes, pick order) this implements.
 */
public class PcBuilder {

    private static final int WATTS_MIN_SIN_GPU = 450;
    private static final int WATTS_MIN_CON_GPU = 650;

    private record Slot(String nombre, String categoria) {}

    // Anchor first: every veto below references the motherboard.
    private static final List<Slot> SLOTS_FIJOS = List.of(
            new Slot("mother", "Motherboard"),
            new Slot("cpu", "CPU"),
            new Slot("ram", "RAM"),
            new Slot("gabinete", "Gabinete"),
            new Slot("fuente", "Fuente"),
            new Slot("almacenamiento", "Almacenamiento"));

    private static final Slot SLOT_GPU = new Slot("gpu", "GPU");

    private static final List<String> ORDEN_FORM_FACTOR = List.of("ITX", "MATX", "ATX", "EATX");

    /** Only used for {@code baseMlScore}, the same "Para ti"/budget-builder ML tiebreak. */
    private final RecommendationService recommendationService;

    public PcBuilder(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    public PcBuild armar(List<Product> productos, double presupuesto, boolean conGpu, Set<String> excluirUrls) {
        if (productos == null) productos = List.of();
        final Set<String> excluir = excluirUrls != null ? excluirUrls : Set.of();

        Map<String, List<Product>> porCategoria = productos.stream()
                .filter(p -> p.categoria() != null)
                .collect(Collectors.groupingBy(Product::categoria));

        List<Slot> slots = new ArrayList<>(SLOTS_FIJOS);
        // GPU is inserted before Almacenamiento — pick order 6, per the design table —
        // and only when the caller opted in; otherwise it never appears anywhere below.
        if (conGpu) slots.add(slots.size() - 1, SLOT_GPU);

        List<PcPick> picks = new ArrayList<>();
        List<String> sinStock = new ArrayList<>();
        List<String> sinCompatible = new ArrayList<>();
        double remainingBudget = presupuesto;
        int wattsMin = conGpu ? WATTS_MIN_CON_GPU : WATTS_MIN_SIN_GPU;

        TechSpecs motherSpecs = TechSpecs.EMPTY;
        String motherDdr = "";

        for (Slot slot : slots) {
            List<Product> pool = porCategoria.getOrDefault(slot.categoria(), List.of());
            if (!excluir.isEmpty()) {
                List<Product> frescos = pool.stream()
                        .filter(p -> !excluir.contains(p.url()))
                        .collect(Collectors.toList());
                if (!frescos.isEmpty()) pool = frescos;
            }
            if (pool.isEmpty()) {
                sinStock.add(slot.nombre());
                continue;
            }

            TechSpecs motherSpecsRef = motherSpecs;
            String motherDdrRef = motherDdr;
            int wattsMinRef = wattsMin;
            List<Product> compatibles = pool.stream()
                    .filter(p -> esCompatible(slot.nombre(), p, motherSpecsRef, motherDdrRef, wattsMinRef))
                    .collect(Collectors.toList());
            if (compatibles.isEmpty()) {
                sinCompatible.add(slot.nombre());
                continue;
            }

            Product elegido;
            if (presupuesto > 0) {
                final double rem = remainingBudget;
                List<Product> affordable = compatibles.stream()
                        .filter(p -> p.precio() <= rem)
                        .collect(Collectors.toList());
                if (!affordable.isEmpty()) {
                    elegido = mejorPick(affordable);
                } else {
                    // Nothing fits: spend as little as possible, not the best rank.
                    elegido = compatibles.stream()
                            .min(Comparator.comparingDouble(Product::precio))
                            .orElseThrow();
                }
                remainingBudget = Math.max(0, remainingBudget - elegido.precio());
            } else {
                elegido = mejorPick(compatibles);
            }

            TechSpecs specs = TechSpecsParser.parse(elegido.nombre(), elegido.categoria());
            if ("mother".equals(slot.nombre())) {
                motherSpecs = specs;
                motherDdr = motherDdr(specs);
            }
            picks.add(toPick(slot.nombre(), elegido, specs));
        }

        double totalEstimado = picks.stream().mapToDouble(PcPick::precio).sum();
        return new PcBuild(picks, sinStock, sinCompatible, presupuesto, totalEstimado);
    }

    /** {@code motherDdr} = the board's own DDR if it parsed, else derived from its socket. */
    private String motherDdr(TechSpecs mother) {
        if (!mother.ddr().isEmpty()) return mother.ddr();
        return switch (mother.socket()) {
            case "AM5", "LGA1851" -> "DDR5";
            case "AM4" -> "DDR4";
            default -> ""; // LGA1700 is a mixed platform (phase-1 finding) — stays abstained
        };
    }

    private boolean esCompatible(String slotNombre, Product candidato, TechSpecs motherSpecs,
                                 String motherDdr, int wattsMin) {
        TechSpecs specs = TechSpecsParser.parse(candidato.nombre(), candidato.categoria());
        return switch (slotNombre) {
            case "cpu" -> !vetaSocket(specs.socket(), motherSpecs.socket());
            case "ram" -> !vetaDdr(specs.ddr(), motherDdr);
            case "gabinete" -> !vetaFormFactor(specs.formFactor(), motherSpecs.formFactor());
            case "fuente" -> !vetaWatts(specs.watts(), wattsMin);
            default -> true; // mother, gpu, almacenamiento: no rule references these slots
        };
    }

    private boolean vetaSocket(String cpuSocket, String motherSocket) {
        return !cpuSocket.isEmpty() && !motherSocket.isEmpty() && !cpuSocket.equals(motherSocket);
    }

    private boolean vetaDdr(String ramDdr, String motherDdr) {
        return !ramDdr.isEmpty() && !motherDdr.isEmpty() && !ramDdr.equals(motherDdr);
    }

    private boolean vetaFormFactor(String gabineteFormFactor, String motherFormFactor) {
        if (gabineteFormFactor.isEmpty() || motherFormFactor.isEmpty()) return false;
        return ORDEN_FORM_FACTOR.indexOf(gabineteFormFactor) < ORDEN_FORM_FACTOR.indexOf(motherFormFactor);
    }

    private boolean vetaWatts(int fuenteWatts, int minimo) {
        return fuenteWatts != 0 && fuenteWatts < minimo;
    }

    /** rank -baseMlScore desc, precio asc, url asc — same tiebreak as the "Para ti" feed. */
    private Product mejorPick(List<Product> candidatos) {
        return candidatos.stream()
                .min(Comparator
                        .comparingDouble((Product p) -> -recommendationService.baseMlScore(p))
                        .thenComparingDouble(Product::precio)
                        .thenComparing(PcBuilder::urlDe))
                .orElseThrow();
    }

    private static String urlDe(Product p) {
        return p.url() != null ? p.url() : "";
    }

    private PcPick toPick(String slot, Product p, TechSpecs specs) {
        String img = p.imagenUrl() != null ? p.imagenUrl() : "";
        if (img.startsWith("//")) img = "https:" + img;
        return new PcPick(slot,
                p.sitio() != null ? p.sitio() : "",
                p.nombre() != null ? p.nombre() : "",
                p.precio(),
                p.url() != null ? p.url() : "",
                img,
                p.marca() != null ? p.marca() : "",
                specs);
    }
}
