package ar.scraper.pcs;

import java.util.List;

/**
 * Hardware-compatibility attributes read off a PC part's name. Mirrors
 * {@code Product.VisualAttrs}'s abstention policy: every field is fill-only,
 * {@code ""}/{@code 0}/{@code Gama.DESCONOCIDA}/{@code Certificacion.NINGUNA}/
 * {@code TipoAlmacenamiento.DESCONOCIDO}/{@code List.of()} means "the parser
 * didn't assert this", never "no socket"/"no watts". A category only fills
 * the fields it can actually read off the name — see {@link TechSpecsParser}
 * for which.
 */
public record TechSpecs(
        String socket,       // "AM4" | "AM5" | "LGA1700" | "LGA1851" | ""
        String ddr,          // "DDR3" | "DDR4" | "DDR5" | ""
        String formFactor,   // "ITX" | "MATX" | "ATX" | "EATX" | ""
        int watts,
        int capacidadGb,
        String tipoMemoria,  // "DIMM" | "SODIMM" | ""
        Gama gama,
        Certificacion certificacion,
        int velocidadMhz,    // RAM only; 0 = abstención
        TipoAlmacenamiento tipoAlmacenamiento, // Almacenamiento only
        List<String> socketsSoportados, // Cooler only; empty = abstención
        String marcaChip, // "INTEL" | "AMD" | "NVIDIA" | "" abstención
        int generacion,   // 0 = abstención
        int tierChipset,  // Motherboard only; 0 = abstención; 1 = X/Z, 2 = B, 3 = A/H — menor es mejor, mapeado explícito nunca por valor
        int modulos,      // RAM only; 0 = abstención
        boolean wifi      // Motherboard only; false es una AFIRMACIÓN ("no trae wifi"), nunca abstención — D2, la excepción
) {
    public static final TechSpecs EMPTY =
            new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA,
                    0, TipoAlmacenamiento.DESCONOCIDO, List.of(), "", 0, 0, 0, false);

    /**
     * Pre-{@code pc-builder-gama} shape, kept so callers that only ever read
     * socket/ddr/formFactor/watts/capacidadGb/tipoMemoria (PcsEndpoints,
     * PcBuildJsonTest, DatabaseServiceSavedPcsTest) keep compiling untouched
     * — refactor contract, CODE-2. The four new fields default to their
     * abstention values, same as EMPTY.
     */
    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, Gama.DESCONOCIDA, Certificacion.NINGUNA);
    }

    /**
     * pc-builder-gama T3a shape (8 args, the record's canonical constructor
     * before T3b added velocidadMhz/tipoAlmacenamiento): kept so callers
     * built against that shape (the readers that don't touch RAM/
     * Almacenamiento, and the T3a rule tests) keep compiling untouched —
     * refactor contract, CODE-2. The two new fields default to their
     * abstention values, same as EMPTY.
     */
    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion,
                0, TipoAlmacenamiento.DESCONOCIDO);
    }

    /**
     * pc-builder-gama T3b shape (10 args, the record's canonical constructor
     * before T2d added socketsSoportados): kept so callers built against
     * that shape keep compiling untouched — refactor contract, CODE-2. The
     * new field defaults to empty (abstención), same as EMPTY.
     */
    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion, int velocidadMhz, TipoAlmacenamiento tipoAlmacenamiento) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion,
                velocidadMhz, tipoAlmacenamiento, List.of());
    }

    /**
     * pc-builder-deep-taxonomy T2d shape (11 args, the record's canonical
     * constructor before T3a added marcaChip/generacion/tierChipset/
     * modulos/wifi): kept so every existing reader and the T1/T2 rule tests
     * keep compiling untouched — refactor contract, CODE-2. The five new
     * fields default to their abstention values, same as EMPTY (wifi=false
     * included — D2 exception, but a reader that hasn't looked yet has no
     * more grounds to assert "no wifi" than to assert anything else).
     */
    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion, int velocidadMhz, TipoAlmacenamiento tipoAlmacenamiento,
            List<String> socketsSoportados) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion,
                velocidadMhz, tipoAlmacenamiento, socketsSoportados, "", 0, 0, 0, false);
    }
}
