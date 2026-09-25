package ar.scraper.pcs;

import ar.scraper.model.Product;
import ar.scraper.pcs.reglas.ReglaCapacidadMinima;
import ar.scraper.pcs.reglas.ReglaCertificacion;
import ar.scraper.pcs.reglas.ReglaCompatibilidad;
import ar.scraper.pcs.reglas.ReglaDdr;
import ar.scraper.pcs.reglas.ReglaDdrPedidaMother;
import ar.scraper.pcs.reglas.ReglaDdrPedidaRam;
import ar.scraper.pcs.reglas.ReglaFormFactor;
import ar.scraper.pcs.reglas.ReglaGama;
import ar.scraper.pcs.reglas.ReglaMarcaChip;
import ar.scraper.pcs.reglas.ReglaPlataformaConCpu;
import ar.scraper.pcs.reglas.ReglaRamDual;
import ar.scraper.pcs.reglas.ReglaSocket;
import ar.scraper.pcs.reglas.ReglaSocketCooler;
import ar.scraper.pcs.reglas.ReglaSodimm;
import ar.scraper.pcs.reglas.ReglaTamanioGabinete;
import ar.scraper.pcs.reglas.ReglaTipoAlmacenamiento;
import ar.scraper.pcs.reglas.ReglaTipoCoolerPedido;
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

    /**
     * Opens for gama ALTA (D4, T3b-2) — a decision about the requested tier,
     * never about the cpu pick itself: "and the CPU doesn't include a cooler"
     * fell in T1 (309/313 CPUs say nothing about a cooler either way, see
     * CLAUDE.md "coolerIncluido no existe"). ReglaSocketCooler (D6, T2d)
     * vetoes when the cooler names sockets and the mother's socket isn't
     * among them; either side unparsed abstains, same as every other rule.
     *
     * <p>Fase 9 (D4) adds the second way in: asking for a cooling technology
     * opens the slot at any tier. Asking for liquid cooling and getting a
     * build with no cooler in it does not answer the question that was
     * asked. Built fresh per call, same reason as {@link #slotsFijos}: the
     * requested tipo bakes into {@link ReglaTipoCoolerPedido#motivo()}.</p>
     */
    private static SlotDeArmado slotCooler(PreferenciasDeArmado prefs) {
        return new SlotDeArmado("cooler", "Cooler",
                List.of(new ReglaSocketCooler(), new ReglaTipoCoolerPedido(prefs.tipoCooler())),
                new CriterioPorEjesTecnicos(EjesTecnicos.COOLER));
    }

    public PcBuilder() {
    }

    /** Pre-{@code pc-builder-gama} shape: no gama requested — see the 5-arg overload. */
    // "Combo ..." o un "+" seguido de otro componente que el armado compra por separado.
    // "80 + Gold" o "+ Wraith Cooler" (el cooler de caja) no suman nada.
    private static final java.util.regex.Pattern COMBO = java.util.regex.Pattern.compile(
            "\\bcombo\\b|\\+\\s*(kit|procesador|micro|cpu|mother|motherboard|memoria|ram|monitor|fuente|teclado|mouse|auricular)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    static boolean esCombo(String nombre) {
        return nombre != null && COMBO.matcher(nombre).find();
    }

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
     * {@code Uso.GAMING} — see the 7-arg overload for {@link Uso#HOMELAB}.
     */
    public PcBuild armar(List<Product> productos, double presupuesto, boolean conGpu, Set<String> excluirUrls,
            Gama gamaPedida, PreferenciasDeArmado prefs) {
        return armar(productos, presupuesto, conGpu, excluirUrls, gamaPedida, prefs, Uso.GAMING);
    }

    /**
     * {@code uso} picks the whole slot layout (D3/D4, pc-builder-homelab
     * T3): {@link Uso#GAMING} is the 6-arg overload's shape, byte for byte.
     * {@link Uso#HOMELAB} swaps in a second storage slot ({@code sistema} +
     * {@code datos}) and ranks RAM by capacity first — same vetoes, same
     * cooler/gpu insertion rule. Combined with {@code
     * prefs.tamanioGabinete() == TamanioGabinete.MINI} it collapses into the
     * mini-PC mode instead (D6): a single {@code minipc} pick plus {@code
     * datos}, no mother/cpu/ram/gabinete/fuente/cooler/gpu.
     */
    public PcBuild armar(List<Product> productos, double presupuesto, boolean conGpu, Set<String> excluirUrls,
            Gama gamaPedida, PreferenciasDeArmado prefs, Uso uso) {
        if (productos == null) productos = List.of();
        final Set<String> excluir = excluirUrls != null ? excluirUrls : Set.of();
        final PreferenciasDeArmado preferencias = prefs != null ? prefs : PreferenciasDeArmado.NINGUNA;
        final Uso usoResuelto = uso != null ? uso : Uso.GAMING;

        Map<String, List<Product>> porCategoria = productos.stream()
                .filter(p -> p.categoria() != null)
                .collect(Collectors.groupingBy(Product::categoria));

        List<SlotDeArmado> slots;
        if (usoResuelto == Uso.HOMELAB && preferencias.tamanioGabinete() == TamanioGabinete.MINI) {
            // D6: un mini PC es una unidad completa, no componentes sueltos —
            // ni cooler ni gpu se insertan acá, sin importar gama/conGpu.
            slots = new ArrayList<>(slotsMiniPc(preferencias));
        } else if (usoResuelto == Uso.HOMELAB) {
            slots = new ArrayList<>(slotsFijosHomelab(preferencias));
            insertarCoolerYGpu(slots, gamaPedida, conGpu, preferencias);
        } else {
            slots = new ArrayList<>(slotsFijos(preferencias));
            insertarCoolerYGpu(slots, gamaPedida, conGpu, preferencias);
        }

        List<PcPick> picks = new ArrayList<>();
        List<String> sinStock = new ArrayList<>();
        List<String> sinCompatible = new ArrayList<>();
        Map<String, String> mensajes = new LinkedHashMap<>();
        // D3: con presupuesto, cada slot ve su cuota más lo que los anteriores
        // dejaron sin gastar — nunca el restante entero, que dejaba al primer
        // slot caro vaciarle la caja a todos los que vienen después.
        CuotasDePresupuesto cuotas = CuotasDePresupuesto.para(slots, usoResuelto);
        double arrastre = 0;
        // D5: el piso pedido SUBE el del armado, nunca lo baja. El de la gama
        // es un piso de seguridad del armado (una GPU de gama alta consume lo
        // que consume), el pedido es del usuario; manda el más alto.
        int wattsMin = Math.max(EstimadorDeConsumo.wattsMinimos(gamaPedida, conGpu),
                preferencias.wattsMinimos() != null ? preferencias.wattsMinimos() : 0);
        Certificacion certMin = EstimadorDeConsumo.certificacionMinima(gamaPedida);
        // T13: sólo tiene sentido calcular esto cuando hay un slot "mother"
        // que leerlo (no en modo mini PC) y se pidió una gama — sin gama,
        // ReglaGama no filtra nada y todo socket "calificaría" igual.
        Set<String> socketsElegibles = (gamaPedida != null && indiceDeSiExiste(slots, "cpu") >= 0)
                ? socketsConCpuElegible(porCategoria, excluir, gamaPedida, preferencias)
                : Set.of();
        // T18: "sin presupuesto" es el modo top-top (D3/D4, fase 8) — invierte
        // el desempate de precio de cada slot a favor del más caro. Se
        // calcula acá, UNA vez, porque contexto es lo único que CriterioPorEjesTecnicos
        // recibe por llamada.
        ContextoDeArmado contexto =
                ContextoDeArmado.inicial(wattsMin, gamaPedida, certMin, preferencias, socketsElegibles,
                        presupuesto <= 0);

        for (SlotDeArmado slot : slots) {
            List<Product> pool = porCategoria.getOrDefault(slot.categoria(), List.of());
            if (!excluir.isEmpty()) {
                List<Product> frescos = pool.stream()
                        .filter(p -> !excluir.contains(p.url()))
                        .collect(Collectors.toList());
                if (!frescos.isEmpty()) pool = frescos;
            }
            // D4: dos slots homelab (sistema/datos) comparten categoría
            // Almacenamiento — sin esto elegirían el mismo disco dos veces. A
            // diferencia de `excluir` (soft: cae si vacía el pool), esto es
            // DURO: el mismo producto físico no puede ser dos picks del mismo
            // armado. No-op en gaming, donde ningún par de slots comparte
            // categoría — ningún pick ya elegido puede estar en este pool.
            // Soft, como `excluir`: un combo sólo entra si es lo único del slot.
            List<Product> sinCombos = pool.stream().filter(p -> !esCombo(p.nombre())).collect(Collectors.toList());
            if (!sinCombos.isEmpty()) pool = sinCombos;
            if (!picks.isEmpty()) {
                Set<String> yaElegidos = picks.stream().map(PcPick::url).collect(Collectors.toSet());
                pool = pool.stream().filter(p -> !yaElegidos.contains(p.url())).collect(Collectors.toList());
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
        int i = indiceDeSiExiste(slots, nombre);
        if (i < 0) throw new IllegalStateException("slot inexistente: " + nombre);
        return i;
    }

    /** T13: como {@link #indiceDe}, pero -1 en vez de tirar — el modo mini PC no tiene slot "cpu". */
    private static int indiceDeSiExiste(List<SlotDeArmado> slots, String nombre) {
        for (int i = 0; i < slots.size(); i++) if (slots.get(i).nombre().equals(nombre)) return i;
        return -1;
    }

    /**
     * T13: sockets cuya plataforma tiene al menos un CPU candidato en el
     * pool que alcanza {@code gamaPedida} y la marca pedida — calculado UNA
     * vez antes de elegir la mother, porque sólo acá se conoce el pool de
     * CPU completo. Ignora a propósito {@link ReglaSocket} (es justo el
     * emparejamiento que está bajo prueba) — sólo mira gama y marca, y
     * exige socket legible: un CPU sin socket no puede afirmar una
     * plataforma, aunque el slot cpu igual lo dejaría pasar por abstención.
     * Vacío ⇒ "ninguna plataforma calificó" ⇒ {@link ReglaPlataformaConCpu}
     * cae al fallback sin restricción (D del task: nunca vetea TODO).
     */
    private static Set<String> socketsConCpuElegible(Map<String, List<Product>> porCategoria, Set<String> excluir,
            Gama gamaPedida, PreferenciasDeArmado preferencias) {
        List<Product> cpuPool = porCategoria.getOrDefault("CPU", List.of());
        if (!excluir.isEmpty()) {
            List<Product> frescos = cpuPool.stream().filter(p -> !excluir.contains(p.url()))
                    .collect(Collectors.toList());
            if (!frescos.isEmpty()) cpuPool = frescos;
        }
        String marcaCpuPedida = preferencias.marcaCpu();
        Set<String> sockets = new LinkedHashSet<>();
        for (Product p : cpuPool) {
            TechSpecs specs = TechSpecsParser.parse(p.nombre(), p.categoria());
            if (specs.socket().isEmpty()) continue;
            if (specs.gama() != gamaPedida) continue;
            if (marcaCpuPedida != null && !marcaCpuPedida.equals(specs.marcaChip())) continue;
            sockets.add(specs.socket());
        }
        return sockets;
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
                                new ReglaWifi(prefs.wifi()), new ReglaPlataformaConCpu()),
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
                // D1, fase 9: ReglaFormFactor (¿entra la mother?) y
                // ReglaTamanioGabinete (¿es del tamaño pedido?) son dos ejes
                // distintos, no uno — ver TamanioGabinete.
                new SlotDeArmado("gabinete", "Gabinete",
                        List.of(new ReglaFormFactor(), new ReglaTamanioGabinete(prefs.tamanioGabinete())),
                        new CriterioPorEjesTecnicos(EjesTecnicos.GABINETE)),
                new SlotDeArmado("fuente", "Fuente", List.of(new ReglaWatts(), new ReglaCertificacion()),
                        new CriterioPorEjesTecnicos(EjesTecnicos.FUENTE)),
                new SlotDeArmado("almacenamiento", "Almacenamiento",
                        List.of(new ReglaTipoAlmacenamiento(prefs.tipoAlmacenamiento()),
                                new ReglaCapacidadMinima(prefs.capacidadMinimaGb())),
                        new CriterioPorEjesTecnicos(EjesTecnicos.ALMACENAMIENTO)));
    }

    /** Built fresh per call, same reason as {@link #slotsFijos} — marcaGpu bakes into ReglaMarcaChip's motivo. */
    private static SlotDeArmado slotGpu(PreferenciasDeArmado prefs) {
        return new SlotDeArmado("gpu", "GPU",
                List.of(new ReglaGama(), new ReglaMarcaChip(prefs.marcaGpu())),
                new CriterioPorEjesTecnicos(EjesTecnicos.GPU));
    }

    /**
     * D4, pc-builder-homelab: mismos slots y mismos vetos que {@link
     * #slotsFijos}, salvo dos diferencias — RAM rankea por capacidad primero
     * ({@link EjesTecnicos#RAM_HOMELAB}) y el almacenamiento se parte en DOS
     * slots que comparten categoría: {@code sistema} (disco de arranque,
     * mismo eje tech-first de siempre) y {@code datos} (volumen a granel,
     * capacidad primero — {@link EjesTecnicos#ALMACENAMIENTO_DATOS}). El
     * hard-exclude de {@code armar} es lo que evita que los dos elijan el
     * mismo disco.
     */
    private static List<SlotDeArmado> slotsFijosHomelab(PreferenciasDeArmado prefs) {
        return List.of(
                new SlotDeArmado("mother", "Motherboard",
                        List.of(new ReglaDdrPedidaMother(prefs.ddr()), new ReglaMarcaChip(prefs.marcaCpu()),
                                new ReglaWifi(prefs.wifi()), new ReglaPlataformaConCpu()),
                        new CriterioPorEjesTecnicos(
                                (ContextoDeArmado ctx) -> EjesTecnicos.mother(ctx.gamaPedida()))),
                new SlotDeArmado("cpu", "CPU",
                        List.of(new ReglaSocket(), new ReglaGama(), new ReglaMarcaChip(prefs.marcaCpu())),
                        new CriterioPorEjesTecnicos(EjesTecnicos.CPU)),
                new SlotDeArmado("ram", "RAM",
                        List.of(new ReglaDdr(), new ReglaSodimm(), new ReglaDdrPedidaRam(prefs.ddr()),
                                new ReglaRamDual(prefs.ramDual())),
                        new CriterioPorEjesTecnicos(EjesTecnicos.RAM_HOMELAB)),
                new SlotDeArmado("gabinete", "Gabinete",
                        List.of(new ReglaFormFactor(), new ReglaTamanioGabinete(prefs.tamanioGabinete())),
                        new CriterioPorEjesTecnicos(EjesTecnicos.GABINETE)),
                new SlotDeArmado("fuente", "Fuente", List.of(new ReglaWatts(), new ReglaCertificacion()),
                        new CriterioPorEjesTecnicos(EjesTecnicos.FUENTE)),
                new SlotDeArmado("sistema", "Almacenamiento",
                        List.of(new ReglaTipoAlmacenamiento(prefs.tipoAlmacenamiento()),
                                new ReglaCapacidadMinima(prefs.capacidadMinimaGb())),
                        new CriterioPorEjesTecnicos(EjesTecnicos.ALMACENAMIENTO)),
                new SlotDeArmado("datos", "Almacenamiento",
                        List.of(new ReglaTipoAlmacenamiento(prefs.tipoAlmacenamiento()),
                                new ReglaCapacidadMinima(prefs.capacidadMinimaGb())),
                        new CriterioPorEjesTecnicos(EjesTecnicos.ALMACENAMIENTO_DATOS)));
    }

    /**
     * D6, pc-builder-homelab: homelab + {@code tamanioGabinete=MINI}
     * colapsa la torre entera en un único pick — un mini PC ya trae su
     * propia mother/cpu/ram/fuente/gabinete integrados, así que esos slots
     * no existen acá. Sólo {@code minipc} (la unidad) y {@code datos} (el
     * disco de volumen, mismo eje que en la torre homelab).
     *
     * <p>{@code ReglaGama} es la única regla del slot minipc — D6 pide
     * respetar la misma regla de abstención-veta que ya usa {@code cpu}: sin
     * gama pedida no filtra nada, con gama pedida un mini PC cuya gama no se
     * pudo leer queda afuera.</p>
     */
    private static List<SlotDeArmado> slotsMiniPc(PreferenciasDeArmado prefs) {
        return List.of(
                new SlotDeArmado("minipc", "Mini PC", List.of(new ReglaGama()),
                        new CriterioPorEjesTecnicos(EjesTecnicos.MINI_PC)),
                new SlotDeArmado("datos", "Almacenamiento",
                        List.of(new ReglaTipoAlmacenamiento(prefs.tipoAlmacenamiento()),
                                new ReglaCapacidadMinima(prefs.capacidadMinimaGb())),
                        new CriterioPorEjesTecnicos(EjesTecnicos.ALMACENAMIENTO_DATOS)));
    }

    /**
     * Cooler y GPU se insertan igual en gaming y en homelab-torre — extraído
     * para no duplicar la lógica (CODE-2: gaming se comporta byte a byte
     * igual que antes). El modo mini PC (D6) nunca llama a esto: ni cooler
     * ni gpu tienen sentido para una unidad integrada.
     */
    private static void insertarCoolerYGpu(List<SlotDeArmado> slots, Gama gamaPedida, boolean conGpu,
            PreferenciasDeArmado preferencias) {
        // Cooler is inserted right after cpu — pick order per the design table —
        // and only for gama ALTA (D4); with null/BAJA/MEDIA/DESCONOCIDA it never
        // appears anywhere below.
        if (gamaPedida == Gama.ALTA || preferencias.tipoCooler() != null) {
            slots.add(indiceDe(slots, "cpu") + 1, slotCooler(preferencias));
        }
        // GPU is inserted before the last slot — pick order 6, per the design
        // table — and only when the caller opted in; otherwise it never
        // appears anywhere below. In homelab that lands it before "datos"
        // (the last of the two storage slots), which is the same "before
        // Almacenamiento" rule the design table already states.
        if (conGpu) slots.add(slots.size() - 1, slotGpu(preferencias));
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
