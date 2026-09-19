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

    private ContextoDeArmado(TechSpecs motherSpecs, String motherDdr, int wattsMin,
            Gama gamaPedida, Certificacion certificacionMinima) {
        this.motherSpecs = motherSpecs;
        this.motherDdr = motherDdr;
        this.wattsMin = wattsMin;
        this.gamaPedida = gamaPedida;
        this.certificacionMinima = certificacionMinima;
    }

    public static ContextoDeArmado inicial(int wattsMin) {
        return new ContextoDeArmado(TechSpecs.EMPTY, "", wattsMin, null, Certificacion.NINGUNA);
    }

    /**
     * {@code gamaPedida == null} significa "no se pidió gama" — un concepto
     * del LLAMADOR, distinto de {@link Gama#DESCONOCIDA} ("el parser no pudo
     * leer la gama de este producto"). Confundirlos haría que un candidato
     * sin gama legible se comporte como si el usuario hubiera pedido
     * DESCONOCIDA, que no es una gama pedible (pc-builder-gama, T3a).
     */
    public static ContextoDeArmado inicial(int wattsMin, Gama gamaPedida, Certificacion certificacionMinima) {
        return new ContextoDeArmado(TechSpecs.EMPTY, "", wattsMin, gamaPedida, certificacionMinima);
    }

    public ContextoDeArmado conMother(TechSpecs motherSpecs) {
        return new ContextoDeArmado(motherSpecs, derivarMotherDdr(motherSpecs), wattsMin, gamaPedida, certificacionMinima);
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

    /** {@code motherDdr} = the board's own DDR if it parsed, else derived from its socket. */
    private static String derivarMotherDdr(TechSpecs mother) {
        if (!mother.ddr().isEmpty()) return mother.ddr();
        return switch (mother.socket()) {
            case "AM5", "LGA1851" -> "DDR5";
            case "AM4" -> "DDR4";
            default -> ""; // LGA1700 is a mixed platform (phase-1 finding) — stays abstained
        };
    }
}
