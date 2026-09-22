package ar.scraper.pcs;

import ar.scraper.model.Product;
import ar.scraper.pcs.reglas.ReglaCertificacion;
import ar.scraper.pcs.reglas.ReglaCompatibilidad;
import ar.scraper.pcs.reglas.ReglaDdr;
import ar.scraper.pcs.reglas.ReglaFormFactor;
import ar.scraper.pcs.reglas.ReglaGama;
import ar.scraper.pcs.reglas.ReglaSocket;
import ar.scraper.pcs.reglas.ReglaSocketCooler;
import ar.scraper.pcs.reglas.ReglaSodimm;
import ar.scraper.pcs.reglas.ReglaWatts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Assembles a PC from the in-memory catalog: one pick per slot, best-effort,
 * with hard compatibility vetoes wherever both sides of a rule parsed. Mirrors
 * {@code SupplementCombo}'s pick/budget/exclude shape, minus the brand tiers —
 * hardware has no analogue for those yet. See odd/tasks/pc-builder.md for the
 * design (slots, vetoes, pick order) this implements.
 *
 * Orchestrates {@link SlotDeArmado}'s rules and {@link CriterioDeSeleccion}'s
 * pick without knowing either's internals — see odd/tasks/pc-builder-gama.md
 * T2 for why this stopped being one class with a string switch.
 */
public class PcBuilder {

    // Anchor first: every veto below references the motherboard.
    // cpu/gpu carry ReglaGama; fuente carries ReglaCertificacion — both are
    // no-ops when no gama was requested (ContextoDeArmado.gamaPedida()==null),
    // so wiring them unconditionally keeps the no-gama path byte-for-byte
    // identical to pre-pc-builder-gama behavior (T3a contract).
    //
    // Each slot carries its own CriterioDeSeleccion (EjesTecnicos, T3b) —
    // price is only ever the tiebreak inside that criterio, never the
    // objective here.
    private static final List<SlotDeArmado> SLOTS_FIJOS = List.of(
            new SlotDeArmado("mother", "Motherboard", List.of(), new CriterioPorEjesTecnicos(EjesTecnicos.MOTHER)),
            new SlotDeArmado("cpu", "CPU", List.of(new ReglaSocket(), new ReglaGama()),
                    new CriterioPorEjesTecnicos(EjesTecnicos.CPU)),
            new SlotDeArmado("ram", "RAM", List.of(new ReglaDdr(), new ReglaSodimm()),
                    new CriterioPorEjesTecnicos(EjesTecnicos.RAM)),
            new SlotDeArmado("gabinete", "Gabinete", List.of(new ReglaFormFactor()),
                    new CriterioPorEjesTecnicos(EjesTecnicos.GABINETE)),
            new SlotDeArmado("fuente", "Fuente", List.of(new ReglaWatts(), new ReglaCertificacion()),
                    new CriterioPorEjesTecnicos(EjesTecnicos.FUENTE)),
            new SlotDeArmado("almacenamiento", "Almacenamiento", List.of(),
                    new CriterioPorEjesTecnicos(EjesTecnicos.ALMACENAMIENTO)));

    private static final SlotDeArmado SLOT_GPU =
            new SlotDeArmado("gpu", "GPU", List.of(new ReglaGama()), new CriterioPorEjesTecnicos(EjesTecnicos.GPU));

    // Opens only for gama ALTA (D4, T3b-2) — depends on the requested tier,
    // never on the cpu pick itself: "and the CPU doesn't include a cooler"
    // fell in T1 (309/313 CPUs say nothing about a cooler either way, see
    // CLAUDE.md "coolerIncluido no existe"). ReglaSocketCooler (D6, T2d)
    // vetoes when the cooler names sockets and the mother's socket isn't
    // among them; either side unparsed abstains, same as every other rule.
    private static final SlotDeArmado SLOT_COOLER =
            new SlotDeArmado("cooler", "Cooler", List.of(new ReglaSocketCooler()),
                    new CriterioPorEjesTecnicos(EjesTecnicos.COOLER));

    public PcBuilder() {
    }

    /** Pre-{@code pc-builder-gama} shape: no gama requested — see the 5-arg overload. */
    public PcBuild armar(List<Product> productos, double presupuesto, boolean conGpu, Set<String> excluirUrls) {
        return armar(productos, presupuesto, conGpu, excluirUrls, null);
    }

    /**
     * {@code gamaPedida == null} means "no gama was requested" — the caller
     * opted out of the tier filter entirely, which is NOT the same as
     * requesting an unparseable tier (that's {@link Gama#DESCONOCIDA}, a
     * per-candidate parser outcome). With null, {@link ReglaGama} and {@link
     * ReglaCertificacion} are no-ops and every slot behaves exactly as
     * before pc-builder-gama.
     */
    public PcBuild armar(List<Product> productos, double presupuesto, boolean conGpu, Set<String> excluirUrls,
            Gama gamaPedida) {
        if (productos == null) productos = List.of();
        final Set<String> excluir = excluirUrls != null ? excluirUrls : Set.of();

        Map<String, List<Product>> porCategoria = productos.stream()
                .filter(p -> p.categoria() != null)
                .collect(Collectors.groupingBy(Product::categoria));

        List<SlotDeArmado> slots = new ArrayList<>(SLOTS_FIJOS);
        // Cooler is inserted right after cpu — pick order per the design table —
        // and only for gama ALTA (D4); with null/BAJA/MEDIA/DESCONOCIDA it never
        // appears anywhere below.
        if (gamaPedida == Gama.ALTA) slots.add(indiceDe(slots, "cpu") + 1, SLOT_COOLER);
        // GPU is inserted before Almacenamiento — pick order 6, per the design table —
        // and only when the caller opted in; otherwise it never appears anywhere below.
        if (conGpu) slots.add(slots.size() - 1, SLOT_GPU);

        List<PcPick> picks = new ArrayList<>();
        List<String> sinStock = new ArrayList<>();
        List<String> sinCompatible = new ArrayList<>();
        Map<String, String> mensajes = new LinkedHashMap<>();
        double remainingBudget = presupuesto;
        int wattsMin = EstimadorDeConsumo.wattsMinimos(gamaPedida, conGpu);
        Certificacion certMin = EstimadorDeConsumo.certificacionMinima(gamaPedida);
        ContextoDeArmado contexto = ContextoDeArmado.inicial(wattsMin, gamaPedida, certMin);

        for (SlotDeArmado slot : slots) {
            List<Product> pool = porCategoria.getOrDefault(slot.categoria(), List.of());
            if (!excluir.isEmpty()) {
                List<Product> frescos = pool.stream()
                        .filter(p -> !excluir.contains(p.url()))
                        .collect(Collectors.toList());
                if (!frescos.isEmpty()) pool = frescos;
            }
            if (pool.isEmpty()) {
                sinStock.add(slot.nombre());
                mensajes.put(slot.nombre(), "no hay productos en la categoría " + slot.categoria());
                continue;
            }

            final ContextoDeArmado contextoActual = contexto;
            List<Product> compatibles = pool.stream()
                    .filter(p -> primeraQueVeta(slot, p, contextoActual).isEmpty())
                    .collect(Collectors.toList());
            if (compatibles.isEmpty()) {
                sinCompatible.add(slot.nombre());
                mensajes.put(slot.nombre(), mensajeSinCompatible(slot, pool, contextoActual));
                continue;
            }

            Product elegido;
            if (presupuesto > 0) {
                final double rem = remainingBudget;
                List<Product> affordable = compatibles.stream()
                        .filter(p -> p.precio() <= rem)
                        .collect(Collectors.toList());
                if (!affordable.isEmpty()) {
                    elegido = slot.criterio().elegir(affordable);
                } else {
                    // Nothing fits: spend as little as possible, not the best rank.
                    elegido = compatibles.stream()
                            .min(Comparator.comparingDouble(Product::precio))
                            .orElseThrow();
                }
                remainingBudget = Math.max(0, remainingBudget - elegido.precio());
            } else {
                elegido = slot.criterio().elegir(compatibles);
            }

            TechSpecs specs = TechSpecsParser.parse(elegido.nombre(), elegido.categoria());
            if ("mother".equals(slot.nombre())) {
                contexto = contexto.conMother(specs);
            }
            picks.add(toPick(slot.nombre(), elegido, specs));
        }

        double totalEstimado = picks.stream().mapToDouble(PcPick::precio).sum();
        return new PcBuild(picks, sinStock, sinCompatible, presupuesto, totalEstimado, mensajes);
    }

    /**
     * The first rule (in the slot's own declared order) that vetoes this
     * candidate, or empty if it passes every rule — {@code allMatch} would
     * short-circuit without saying which rule failed, and D6's sinCompatible
     * message needs exactly that (pc-builder-gama T3b-2).
     */
    private Optional<ReglaCompatibilidad> primeraQueVeta(SlotDeArmado slot, Product candidato, ContextoDeArmado contexto) {
        TechSpecs specs = TechSpecsParser.parse(candidato.nombre(), candidato.categoria());
        return slot.reglas().stream().filter(regla -> !regla.permite(specs, contexto)).findFirst();
    }

    /**
     * D6: joins the distinct {@link ReglaCompatibilidad#motivo()} of whichever
     * rules actually vetoed at least one candidate in {@code pool}, in the
     * slot's own rule order — never candidate order, and never a rule that
     * never fired.
     */
    private String mensajeSinCompatible(SlotDeArmado slot, List<Product> pool, ContextoDeArmado contexto) {
        Set<ReglaCompatibilidad> vetantes = pool.stream()
                .map(p -> primeraQueVeta(slot, p, contexto))
                .flatMap(Optional::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return slot.reglas().stream()
                .filter(vetantes::contains)
                .map(ReglaCompatibilidad::motivo)
                .collect(Collectors.joining(" · "));
    }

    private static int indiceDe(List<SlotDeArmado> slots, String nombre) {
        for (int i = 0; i < slots.size(); i++) if (slots.get(i).nombre().equals(nombre)) return i;
        throw new IllegalStateException("slot inexistente: " + nombre);
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
