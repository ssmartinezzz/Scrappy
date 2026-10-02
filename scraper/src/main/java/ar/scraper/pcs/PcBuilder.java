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

import org.apache.commons.lang3.StringUtils;

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
 * Assembles a PC from the in-memory catalog: one pick per slot, best-effort, with hard
 * compatibility vetoes wherever both sides of a rule parsed.
 */
public class PcBuilder {

    /**
     * Opens for gama ALTA — a decision about the requested tier, never about the cpu pick itself:
     * Asking for liquid cooling and getting a build with no cooler in it does not answer the
     * question that was asked.
     */
    private static SlotDeArmado slotCooler(PreferenciasDeArmado prefs) {
        return new SlotDeArmado("cooler", "Cooler",
                List.of(new ReglaSocketCooler(), new ReglaTipoCoolerPedido(prefs.tipoCooler())),
                new CriterioPorEjesTecnicos(EjesTecnicos.COOLER));
    }

    public PcBuilder() {
    }

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
     * {@code gamaPedida == null} means "no gama was requested" — the caller opted out of the tier
     * filter entirely, which is NOT the same as requesting an unparseable tier (that's
     * {@link Gama#DESCONOCIDA}, a per-candidate parser outcome).
     */
    public PcBuild armar(List<Product> productos, double presupuesto, boolean conGpu, Set<String> excluirUrls,
            Gama gamaPedida) {
        return armar(productos, presupuesto, conGpu, excluirUrls, gamaPedida, PreferenciasDeArmado.NINGUNA);
    }

    /**
     * {@code prefs}' fields default to "not requested" — with {@link PreferenciasDeArmado#NINGUNA}
     * every slot's extra rule is a no-op and this behaves exactly like the 5-arg overload (pinned
     * by {@code PcBuilderPreferenciasTest.ningunaEsIdenticaAlOverloadDe5Args}).
     */
    public PcBuild armar(List<Product> productos, double presupuesto, boolean conGpu, Set<String> excluirUrls,
            Gama gamaPedida, PreferenciasDeArmado prefs) {
        return armar(productos, presupuesto, conGpu, excluirUrls, gamaPedida, prefs, Uso.GAMING);
    }

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
        CuotasDePresupuesto cuotas = CuotasDePresupuesto.para(slots, usoResuelto);
        double arrastre = 0;
        // El de la gama es un piso de seguridad del armado (una GPU de gama alta consume lo que
        // consume), el pedido es del usuario; manda el más alto.
        int wattsMin = Math.max(EstimadorDeConsumo.wattsMinimos(gamaPedida, conGpu),
                preferencias.wattsMinimos() != null ? preferencias.wattsMinimos() : 0);
        Certificacion certMin = EstimadorDeConsumo.certificacionMinima(gamaPedida);
        Set<String> socketsElegibles = (gamaPedida != null && indiceDeSiExiste(slots, "cpu") >= 0)
                ? socketsConCpuElegible(porCategoria, excluir, gamaPedida, preferencias)
                : Set.of();
        // "sin presupuesto" es el modo top-top — invierte el desempate de precio de cada slot a
        // favor del más caro. Se calcula acá, UNA vez, porque contexto es lo único que
        // CriterioPorEjesTecnicos recibe por llamada.
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
            // A diferencia de `excluir` (soft: cae si vacía el pool), esto es DURO: el mismo
            // producto físico no puede ser dos picks del mismo armado. Soft, como `excluir`: un
            // combo sólo entra si es lo único del slot.
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

    private Optional<ReglaCompatibilidad> primeraQueVeta(SlotDeArmado slot, Product candidato, ContextoDeArmado contexto) {
        TechSpecs specs = TechSpecsParser.parse(candidato.nombre(), candidato.categoria());
        return slot.reglas().stream().filter(regla -> !regla.permite(specs, contexto)).findFirst();
    }

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

    private static int indiceDeSiExiste(List<SlotDeArmado> slots, String nombre) {
        for (int i = 0; i < slots.size(); i++) if (slots.get(i).nombre().equals(nombre)) return i;
        return -1;
    }

    /**
     * Ignora a propósito {@link ReglaSocket} (es justo el emparejamiento que está bajo prueba) —
     * sólo mira gama y marca, y exige socket legible: un CPU sin socket no puede afirmar una
     * plataforma, aunque el slot cpu igual lo dejaría pasar por abstención.
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

    /** Existing rules stay first, preference rules after, per slot. */
    private static List<SlotDeArmado> slotsFijos(PreferenciasDeArmado prefs) {
        return List.of(
                slotMother(prefs),
                slotCpu(prefs),
                slotRam(prefs, EjesTecnicos.RAM),
                slotGabinete(prefs),
                slotFuente(),
                slotAlmacenamiento("almacenamiento", prefs, EjesTecnicos.ALMACENAMIENTO));
    }

    private static SlotDeArmado slotMother(PreferenciasDeArmado prefs) {
        return new SlotDeArmado("mother", "Motherboard",
                List.of(new ReglaDdrPedidaMother(prefs.ddr()), new ReglaMarcaChip(prefs.marcaCpu()),
                        new ReglaWifi(prefs.wifi()), new ReglaPlataformaConCpu()),
                new CriterioPorEjesTecnicos((ContextoDeArmado ctx) -> EjesTecnicos.mother(ctx.gamaPedida())));
    }

    private static SlotDeArmado slotCpu(PreferenciasDeArmado prefs) {
        return new SlotDeArmado("cpu", "CPU",
                List.of(new ReglaSocket(), new ReglaGama(), new ReglaMarcaChip(prefs.marcaCpu())),
                new CriterioPorEjesTecnicos(EjesTecnicos.CPU));
    }

    private static SlotDeArmado slotRam(PreferenciasDeArmado prefs, Comparator<TechSpecs> ejes) {
        return new SlotDeArmado("ram", "RAM",
                List.of(new ReglaDdr(), new ReglaSodimm(), new ReglaDdrPedidaRam(prefs.ddr()),
                        new ReglaRamDual(prefs.ramDual())),
                new CriterioPorEjesTecnicos(ejes));
    }

    private static SlotDeArmado slotGabinete(PreferenciasDeArmado prefs) {
        return new SlotDeArmado("gabinete", "Gabinete",
                List.of(new ReglaFormFactor(), new ReglaTamanioGabinete(prefs.tamanioGabinete())),
                new CriterioPorEjesTecnicos(EjesTecnicos.GABINETE));
    }

    private static SlotDeArmado slotFuente() {
        return new SlotDeArmado("fuente", "Fuente", List.of(new ReglaWatts(), new ReglaCertificacion()),
                new CriterioPorEjesTecnicos(EjesTecnicos.FUENTE));
    }

    private static SlotDeArmado slotAlmacenamiento(String id, PreferenciasDeArmado prefs,
            Comparator<TechSpecs> ejes) {
        return new SlotDeArmado(id, "Almacenamiento",
                List.of(new ReglaTipoAlmacenamiento(prefs.tipoAlmacenamiento()),
                        new ReglaCapacidadMinima(prefs.capacidadMinimaGb())),
                new CriterioPorEjesTecnicos(ejes));
    }

    private static SlotDeArmado slotGpu(PreferenciasDeArmado prefs) {
        return new SlotDeArmado("gpu", "GPU",
                List.of(new ReglaGama(), new ReglaMarcaChip(prefs.marcaGpu())),
                new CriterioPorEjesTecnicos(EjesTecnicos.GPU));
    }

    /** El hard-exclude de {@code armar} es lo que evita que los dos elijan el mismo disco. */
    private static List<SlotDeArmado> slotsFijosHomelab(PreferenciasDeArmado prefs) {
        return List.of(
                slotMother(prefs),
                slotCpu(prefs),
                slotRam(prefs, EjesTecnicos.RAM_HOMELAB),
                slotGabinete(prefs),
                slotFuente(),
                slotAlmacenamiento("sistema", prefs, EjesTecnicos.ALMACENAMIENTO),
                slotAlmacenamiento("datos", prefs, EjesTecnicos.ALMACENAMIENTO_DATOS));
    }

    /**
     * Sólo {@code minipc} (la unidad) y {@code datos} (el disco de volumen, mismo eje que en la
     * torre homelab).
     */
    private static List<SlotDeArmado> slotsMiniPc(PreferenciasDeArmado prefs) {
        return List.of(
                new SlotDeArmado("minipc", "Mini PC", List.of(new ReglaGama()),
                        new CriterioPorEjesTecnicos(EjesTecnicos.MINI_PC)),
                slotAlmacenamiento("datos", prefs, EjesTecnicos.ALMACENAMIENTO_DATOS));
    }

    /**
     * El modo mini PC nunca llama a esto: ni cooler ni gpu tienen sentido para una unidad
     * integrada.
     */
    private static void insertarCoolerYGpu(List<SlotDeArmado> slots, Gama gamaPedida, boolean conGpu,
            PreferenciasDeArmado preferencias) {
        if (gamaPedida == Gama.ALTA || preferencias.tipoCooler() != null) {
            slots.add(indiceDe(slots, "cpu") + 1, slotCooler(preferencias));
        }
        if (conGpu) slots.add(slots.size() - 1, slotGpu(preferencias));
    }

    private PcPick toPick(String slot, Product p, TechSpecs specs) {
        String img = StringUtils.defaultString(p.imagenUrl());
        if (img.startsWith("//")) img = "https:" + img;
        return new PcPick(slot,
                StringUtils.defaultString(p.sitio()),
                StringUtils.defaultString(p.nombre()),
                p.precio(),
                StringUtils.defaultString(p.url()),
                img,
                StringUtils.defaultString(p.marca()),
                specs);
    }
}
