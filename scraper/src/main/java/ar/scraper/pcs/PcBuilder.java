package ar.scraper.pcs;

import ar.scraper.model.Product;
import ar.scraper.pcs.reglas.ReglaCertificacion;
import ar.scraper.pcs.reglas.ReglaCompatibilidad;
import ar.scraper.pcs.reglas.ReglaDdr;
import ar.scraper.pcs.reglas.ReglaDdrPedidaMother;
import ar.scraper.pcs.reglas.ReglaDdrPedidaRam;
import ar.scraper.pcs.reglas.ReglaFormFactor;
import ar.scraper.pcs.reglas.ReglaGama;
import ar.scraper.pcs.reglas.ReglaMarcaChip;
import ar.scraper.pcs.reglas.ReglaRamDual;
import ar.scraper.pcs.reglas.ReglaSocket;
import ar.scraper.pcs.reglas.ReglaSocketCooler;
import ar.scraper.pcs.reglas.ReglaSodimm;
import ar.scraper.pcs.reglas.ReglaTipoAlmacenamiento;
import ar.scraper.pcs.reglas.ReglaWatts;
import ar.scraper.pcs.reglas.ReglaWifi;

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
     * before pc-builder-gama. No technical preferences requested either —
     * see the 6-arg overload (pc-builder-deep-taxonomy T4b).
     */
    public PcBuild armar(List<Product> productos, double presupuesto, boolean conGpu, Set<String> excluirUrls,
            Gama gamaPedida) {
        return armar(productos, presupuesto, conGpu, excluirUrls, gamaPedida, PreferenciasDeArmado.NINGUNA);
    }

    /**
     * {@code prefs}' fields default to "not requested" (D1) — with {@link
     * PreferenciasDeArmado#NINGUNA} every slot's extra rule is a no-op and
     * this behaves exactly like the 5-arg overload (pinned by
     * {@code PcBuilderPreferenciasTest.ningunaEsIdenticaAlOverloadDe5Args}).
     */
    public PcBuild armar(List<Product> productos, double presupuesto, boolean conGpu, Set<String> excluirUrls,
            Gama gamaPedida, PreferenciasDeArmado prefs) {
        if (productos == null) productos = List.of();
        final Set<String> excluir = excluirUrls != null ? excluirUrls : Set.of();
        final PreferenciasDeArmado preferencias = prefs != null ? prefs : PreferenciasDeArmado.NINGUNA;

        Map<String, List<Product>> porCategoria = productos.stream()
                .filter(p -> p.categoria() != null)
                .collect(Collectors.groupingBy(Product::categoria));

        List<SlotDeArmado> slots = new ArrayList<>(slotsFijos(preferencias));
        // Cooler is inserted right after cpu — pick order per the design table —
        // and only for gama ALTA (D4); with null/BAJA/MEDIA/DESCONOCIDA it never
        // appears anywhere below.
        if (gamaPedida == Gama.ALTA) slots.add(indiceDe(slots, "cpu") + 1, SLOT_COOLER);
        // GPU is inserted before Almacenamiento — pick order 6, per the design table —
        // and only when the caller opted in; otherwise it never appears anywhere below.
        if (conGpu) slots.add(slots.size() - 1, slotGpu(preferencias));

        List<PcPick> picks = new ArrayList<>();
        List<String> sinStock = new ArrayList<>();
        List<String> sinCompatible = new ArrayList<>();
        Map<String, String> mensajes = new LinkedHashMap<>();
        // D3: con presupuesto, cada slot ve su cuota más lo que los anteriores
        // dejaron sin gastar — nunca el restante entero, que dejaba al primer
        // slot caro vaciarle la caja a todos los que vienen después.
        CuotasDePresupuesto cuotas = CuotasDePresupuesto.para(slots);
        double arrastre = 0;
        int wattsMin = EstimadorDeConsumo.wattsMinimos(gamaPedida, conGpu);
        Certificacion certMin = EstimadorDeConsumo.certificacionMinima(gamaPedida);
        ContextoDeArmado contexto = ContextoDeArmado.inicial(wattsMin, gamaPedida, certMin, preferencias);

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
                final double disponible = cuotas.cuota(slot.nombre(), presupuesto) + arrastre;
                List<Product> affordable = compatibles.stream()
                        .filter(p -> p.precio() <= disponible)
                        .collect(Collectors.toList());
                if (!affordable.isEmpty()) {
                    elegido = slot.criterio().elegir(affordable, contextoActual);
                } else {
                    // D5: nothing fits the quota — spend as little as possible
                    // rather than leave the slot empty. Un armado incompleto es
                    // peor que uno con un componente flojo, y sinCompatible
                    // sigue reservado para los vetos.
                    elegido = compatibles.stream()
                            .min(Comparator.comparingDouble(Product::precio))
                            .orElseThrow();
                }
                arrastre = Math.max(0, disponible - elegido.precio());
            } else {
                elegido = slot.criterio().elegir(compatibles, contextoActual);
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

    /**
     * Built fresh per {@code armar} call — not a static final list like
     * pre-T4b — because the six preference rules (T4b, D1-D3) bake their
     * requested value into their constructor so {@code motivo()} can name
     * it; with {@link PreferenciasDeArmado#NINGUNA} every extra rule is a
     * no-op and never fires, so {@code mensajes} never mentions them
     * (byte-for-byte with pre-T4b — CODE-2). Existing rules stay first,
     * preference rules after, per slot (D1's wiring order).
     */
    private static List<SlotDeArmado> slotsFijos(PreferenciasDeArmado prefs) {
        return List.of(
                new SlotDeArmado("mother", "Motherboard",
                        List.of(new ReglaDdrPedidaMother(prefs.ddr()), new ReglaMarcaChip(prefs.marcaCpu()),
                                new ReglaWifi(prefs.wifi())),
                        // D9, T4c: chipset tier ranked relative to the requested gama —
                        // only known per-call, off ContextoDeArmado.gamaPedida().
                        new CriterioPorEjesTecnicos(
                                (ContextoDeArmado ctx) -> EjesTecnicos.mother(ctx.gamaPedida()))),
                new SlotDeArmado("cpu", "CPU",
                        List.of(new ReglaSocket(), new ReglaGama(), new ReglaMarcaChip(prefs.marcaCpu())),
                        new CriterioPorEjesTecnicos(EjesTecnicos.CPU)),
                new SlotDeArmado("ram", "RAM",
                        List.of(new ReglaDdr(), new ReglaSodimm(), new ReglaDdrPedidaRam(prefs.ddr()),
                                new ReglaRamDual(prefs.ramDual())),
                        new CriterioPorEjesTecnicos(EjesTecnicos.RAM)),
                new SlotDeArmado("gabinete", "Gabinete", List.of(new ReglaFormFactor()),
                        new CriterioPorEjesTecnicos(EjesTecnicos.GABINETE)),
                new SlotDeArmado("fuente", "Fuente", List.of(new ReglaWatts(), new ReglaCertificacion()),
                        new CriterioPorEjesTecnicos(EjesTecnicos.FUENTE)),
                new SlotDeArmado("almacenamiento", "Almacenamiento",
                        List.of(new ReglaTipoAlmacenamiento(prefs.tipoAlmacenamiento())),
                        new CriterioPorEjesTecnicos(EjesTecnicos.ALMACENAMIENTO)));
    }

    /** Built fresh per call, same reason as {@link #slotsFijos} — marcaGpu bakes into ReglaMarcaChip's motivo. */
    private static SlotDeArmado slotGpu(PreferenciasDeArmado prefs) {
        return new SlotDeArmado("gpu", "GPU",
                List.of(new ReglaGama(), new ReglaMarcaChip(prefs.marcaGpu())),
                new CriterioPorEjesTecnicos(EjesTecnicos.GPU));
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
