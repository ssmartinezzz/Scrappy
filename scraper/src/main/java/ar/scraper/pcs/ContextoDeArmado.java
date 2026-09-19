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

    private ContextoDeArmado(TechSpecs motherSpecs, String motherDdr, int wattsMin) {
        this.motherSpecs = motherSpecs;
        this.motherDdr = motherDdr;
        this.wattsMin = wattsMin;
    }

    public static ContextoDeArmado inicial(int wattsMin) {
        return new ContextoDeArmado(TechSpecs.EMPTY, "", wattsMin);
    }

    public ContextoDeArmado conMother(TechSpecs motherSpecs) {
        return new ContextoDeArmado(motherSpecs, derivarMotherDdr(motherSpecs), wattsMin);
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
