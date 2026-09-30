package ar.scraper.pcs;

import java.util.Set;

/**
 * What the build already decided while walking the slots: the chosen motherboard's specs, its
 * derived DDR generation, and the watts floor for this build.
 */
public final class ContextoDeArmado {

    private final TechSpecs motherSpecs;
    private final String motherDdr;
    private final int wattsMin;
    private final Gama gamaPedida;
    private final Certificacion certificacionMinima;
    private final PreferenciasDeArmado preferencias;
    private final Set<String> socketsConCpuElegible;
    private final boolean sinPresupuesto;

    private ContextoDeArmado(TechSpecs motherSpecs, String motherDdr, int wattsMin,
            Gama gamaPedida, Certificacion certificacionMinima, PreferenciasDeArmado preferencias,
            Set<String> socketsConCpuElegible, boolean sinPresupuesto) {
        this.motherSpecs = motherSpecs;
        this.motherDdr = motherDdr;
        this.wattsMin = wattsMin;
        this.gamaPedida = gamaPedida;
        this.certificacionMinima = certificacionMinima;
        this.preferencias = preferencias;
        this.socketsConCpuElegible = socketsConCpuElegible;
        this.sinPresupuesto = sinPresupuesto;
    }

    public static ContextoDeArmado inicial(int wattsMin) {
        return inicial(wattsMin, null, Certificacion.NINGUNA, PreferenciasDeArmado.NINGUNA);
    }

    /**
     * {@code gamaPedida == null} significa "no se pidió gama" — un concepto del LLAMADOR, distinto
     * de {@link Gama#DESCONOCIDA} ("el parser no pudo leer la gama de este producto").
     */
    public static ContextoDeArmado inicial(int wattsMin, Gama gamaPedida, Certificacion certificacionMinima) {
        return inicial(wattsMin, gamaPedida, certificacionMinima, PreferenciasDeArmado.NINGUNA);
    }

    public static ContextoDeArmado inicial(int wattsMin, Gama gamaPedida, Certificacion certificacionMinima,
            PreferenciasDeArmado preferencias) {
        return inicial(wattsMin, gamaPedida, certificacionMinima, preferencias, Set.of());
    }

    /**
     * {@code socketsConCpuElegible} is the set of platforms (sockets) whose CPU pool has at least
     * one candidate matching {@code gamaPedida}/{@code preferencias.marcaCpu()} — computed once by
     * {@code PcBuilder} before any slot is picked, since only it knows the CPU pool.
     */
    public static ContextoDeArmado inicial(int wattsMin, Gama gamaPedida, Certificacion certificacionMinima,
            PreferenciasDeArmado preferencias, Set<String> socketsConCpuElegible) {
        return inicial(wattsMin, gamaPedida, certificacionMinima, preferencias, socketsConCpuElegible, false);
    }

    /**
     * {@code false} en todo overload previo preserva el comportamiento de siempre (desempate de
     * precio ascendente); sólo con {@code true} {@link CriterioPorEjesTecnicos} invierte el
     * desempate a descendente — "el modo top top" elige el mejor de cada slot, y entre dos
     * candidatos empatados en el eje técnico el más caro es, a igualdad de todo lo demás, la mejor
     * pieza disponible.
     */
    public static ContextoDeArmado inicial(int wattsMin, Gama gamaPedida, Certificacion certificacionMinima,
            PreferenciasDeArmado preferencias, Set<String> socketsConCpuElegible, boolean sinPresupuesto) {
        return new ContextoDeArmado(TechSpecs.EMPTY, "", wattsMin, gamaPedida, certificacionMinima, preferencias,
                socketsConCpuElegible, sinPresupuesto);
    }

    public ContextoDeArmado conMother(TechSpecs motherSpecs) {
        return new ContextoDeArmado(motherSpecs, derivarMotherDdr(motherSpecs), wattsMin, gamaPedida,
                certificacionMinima, preferencias, socketsConCpuElegible, sinPresupuesto);
    }

    public TechSpecs motherSpecs() {
        return motherSpecs;
    }

    public String motherDdr() {
        return motherDdr;
    }

    public int wattsMin() {
        return wattsMin;
    }

    /**
     * Null cuando no se pidió gama — ver el javadoc de {@link #inicial(int, Gama, Certificacion)}.
     */
    public Gama gamaPedida() {
        return gamaPedida;
    }

    public Certificacion certificacionMinima() {
        return certificacionMinima;
    }

    /** Never null — {@link PreferenciasDeArmado#NINGUNA} when nothing was requested. */
    public PreferenciasDeArmado preferencias() {
        return preferencias;
    }

    /** Never null — empty means "no platform restriction". */
    public Set<String> socketsConCpuElegible() {
        return socketsConCpuElegible;
    }

    public boolean sinPresupuesto() {
        return sinPresupuesto;
    }

    public static String derivarMotherDdr(TechSpecs mother) {
        if (!mother.ddr().isEmpty()) return mother.ddr();
        return switch (mother.socket()) {
            case "AM5", "LGA1851" -> "DDR5";
            case "AM4", "LGA1200" -> "DDR4"; // LGA1200 (10th/11th gen) is DDR4-only, unambiguous
            default -> "";
        };
    }
}
