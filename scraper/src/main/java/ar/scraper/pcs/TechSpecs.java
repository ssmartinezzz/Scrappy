package ar.scraper.pcs;

/**
 * Hardware-compatibility attributes read off a PC part's name. Mirrors
 * {@code Product.VisualAttrs}'s abstention policy: every field is fill-only,
 * {@code ""}/{@code 0}/{@code Gama.DESCONOCIDA}/{@code Certificacion.NINGUNA}
 * means "the parser didn't assert this", never "no socket"/"no watts". A
 * category only fills the fields it can actually read off the name — see
 * {@link TechSpecsParser} for which.
 */
public record TechSpecs(
        String socket,       // "AM4" | "AM5" | "LGA1700" | "LGA1851" | ""
        String ddr,          // "DDR3" | "DDR4" | "DDR5" | ""
        String formFactor,   // "ITX" | "MATX" | "ATX" | "EATX" | ""
        int watts,
        int capacidadGb,
        String tipoMemoria,  // "DIMM" | "SODIMM" | ""
        Gama gama,
        Certificacion certificacion
) {
    public static final TechSpecs EMPTY =
            new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA);

    /**
     * Pre-{@code pc-builder-gama} shape, kept so callers that only ever read
     * socket/ddr/formFactor/watts/capacidadGb/tipoMemoria (PcsEndpoints,
     * PcBuildJsonTest, DatabaseServiceSavedPcsTest) keep compiling untouched
     * — refactor contract, CODE-2. The two new fields default to their
     * abstention values, same as EMPTY.
     */
    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, Gama.DESCONOCIDA, Certificacion.NINGUNA);
    }
}
