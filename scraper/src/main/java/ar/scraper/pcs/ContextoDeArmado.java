package ar.scraper.pcs;

import java.util.Set;

/**
 * What the build already decided while walking the slots: the chosen
 * motherboard's specs, its derived DDR generation, and the watts floor for
 * this build. Immutable — {@link #conMother} returns a new context rather
 * than mutating this one.
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
     * {@code gamaPedida == null} significa "no se pidió gama" — un concepto
     * del LLAMADOR, distinto de {@link Gama#DESCONOCIDA} ("el parser no pudo
     * leer la gama de este producto"). Confundirlos haría que un candidato
     * sin gama legible se comporte como si el usuario hubiera pedido
     * DESCONOCIDA, que no es una gama pedible (pc-builder-gama, T3a).
     */
    public static ContextoDeArmado inicial(int wattsMin, Gama gamaPedida, Certificacion certificacionMinima) {
        return inicial(wattsMin, gamaPedida, certificacionMinima, PreferenciasDeArmado.NINGUNA);
    }

    /** T4a, pc-builder-deep-taxonomy: carries the caller's requested technical preferences (D1). */
    public static ContextoDeArmado inicial(int wattsMin, Gama gamaPedida, Certificacion certificacionMinima,
            PreferenciasDeArmado preferencias) {
        return inicial(wattsMin, gamaPedida, certificacionMinima, preferencias, Set.of());
    }

    /**
     * T13, pc-builder-homelab: {@code socketsConCpuElegible} is the set of
     * platforms (sockets) whose CPU pool has at least one candidate matching
     * {@code gamaPedida}/{@code preferencias.marcaCpu()} — computed once by
     * {@code PcBuilder} before any slot is picked, since only it knows the
     * CPU pool. Empty means "no restriction" (either nothing was computed,
     * pre-T13 callers, or no platform qualified — the fallback {@link
     * ar.scraper.pcs.reglas.ReglaPlataformaConCpu} relies on).
     */
    public static ContextoDeArmado inicial(int wattsMin, Gama gamaPedida, Certificacion certificacionMinima,
            PreferenciasDeArmado preferencias, Set<String> socketsConCpuElegible) {
        return inicial(wattsMin, gamaPedida, certificacionMinima, preferencias, socketsConCpuElegible, false);
    }

    /**
     * T18, pc-builder-homelab: {@code sinPresupuesto} es un hecho del
     * LLAMADOR ({@code presupuesto <= 0} en {@link PcBuilder#armar}), no una
     * abstención de parseo — por eso no tiene el molde null/vacío de
     * {@code gamaPedida}/{@code socketsConCpuElegible}. {@code false} en
     * todo overload previo preserva el comportamiento de siempre (desempate
     * de precio ascendente); sólo con {@code true} {@link
     * CriterioPorEjesTecnicos} invierte el desempate a descendente — "el
     * modo top top" (D3/D4, fase 8) elige el mejor de cada slot, y entre dos
     * candidatos empatados en el eje técnico el más caro es, a igualdad de
     * todo lo demás, la mejor pieza disponible.
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

    /** Null cuando no se pidió gama — ver el javadoc de {@link #inicial(int, Gama, Certificacion)}. */
    public Gama gamaPedida() {
        return gamaPedida;
    }

    public Certificacion certificacionMinima() {
        return certificacionMinima;
    }

    /** Never null — {@link PreferenciasDeArmado#NINGUNA} when nothing was requested (D1). */
    public PreferenciasDeArmado preferencias() {
        return preferencias;
    }

    /** Never null — empty means "no platform restriction" (D fallback, T13). */
    public Set<String> socketsConCpuElegible() {
        return socketsConCpuElegible;
    }

    /** T18: {@code presupuesto <= 0} en el llamador — "modo top top" (D3/D4, fase 8). */
    public boolean sinPresupuesto() {
        return sinPresupuesto;
    }

    /**
     * {@code motherDdr} = the board's own DDR if it parsed, else derived
     * from its socket. Public (not package-private) since T4b's {@code
     * ReglaDdrPedidaMother} (package {@code ar.scraper.pcs.reglas}) needs to
     * derive a MOTHER CANDIDATE's own DDR the same way {@link
     * EjesTecnicos#MOTHER} ranks it — two copies of a socket→DDR mapping
     * diverge in silence (pc-builder-gama T3b).
     */
    public static String derivarMotherDdr(TechSpecs mother) {
        if (!mother.ddr().isEmpty()) return mother.ddr();
        return switch (mother.socket()) {
            case "AM5", "LGA1851" -> "DDR5";
            case "AM4", "LGA1200" -> "DDR4"; // LGA1200 (10th/11th gen) is DDR4-only, unambiguous
            // LGA1700 is mixed (phase-1 finding); LGA1151 and AM3 both span two DDR
            // generations across their boards — all three stay abstained
            default -> "";
        };
    }
}
