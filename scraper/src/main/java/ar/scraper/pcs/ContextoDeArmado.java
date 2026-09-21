package ar.scraper.pcs;

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

    private ContextoDeArmado(TechSpecs motherSpecs, String motherDdr, int wattsMin,
            Gama gamaPedida, Certificacion certificacionMinima, PreferenciasDeArmado preferencias) {
        this.motherSpecs = motherSpecs;
        this.motherDdr = motherDdr;
        this.wattsMin = wattsMin;
        this.gamaPedida = gamaPedida;
        this.certificacionMinima = certificacionMinima;
        this.preferencias = preferencias;
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
        return new ContextoDeArmado(TechSpecs.EMPTY, "", wattsMin, gamaPedida, certificacionMinima, preferencias);
    }

    public ContextoDeArmado conMother(TechSpecs motherSpecs) {
        return new ContextoDeArmado(motherSpecs, derivarMotherDdr(motherSpecs), wattsMin, gamaPedida,
                certificacionMinima, preferencias);
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
