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
        boolean wifi,     // Motherboard only; false es una AFIRMACIÓN ("no trae wifi"), nunca abstención — D2, la excepción
        TipoCooler tipoCooler, // Cooler only; DESCONOCIDO = abstención
        int nivel,        // CPU/GPU only; 0 = abstención. El escalón DENTRO de la gama, y la ÚNICA
                          // magnitud de potencia comparable entre marcas: CPU 9|7|5|3 (i9 ≡ Ryzen 9),
                          // GPU 90|80|70|60|50 (RTX 5080 ≡ RX 9080). `generacion` NO lo es — ver
                          // EjesTecnicos.anioDe y odd/tasks/pc-builder-top-tier.md D1.
        TamanioGabinete tamanioGabinete, // Gabinete only; DESCONOCIDO = abstención. Eje DISTINTO de
                          // formFactor (D1, fase 9): el tamaño de torre es cuánto ocupa, el form
                          // factor es qué placa entra. El veto Gabinete ⊇ Mother sigue en formFactor.
        int radiadorMm,   // Cooler only; 0 = abstención. Sólo un token entero dígitos+mm cuenta.
        ClaseDisipador claseDisipador, // Cooler AIRE only; DESCONOCIDA = abstención (T16). No se
                          // persiste en producto_tech_specs — mismo precedente que nivel (D7).
        int heatpipes     // Cooler AIRE only; 0 = abstención (T16).
) {
    public static final TechSpecs EMPTY =
            new TechSpecs("", "", "", 0, 0, "", Gama.DESCONOCIDA, Certificacion.NINGUNA,
                    0, TipoAlmacenamiento.DESCONOCIDO, List.of(), "", 0, 0, 0, false, TipoCooler.DESCONOCIDO, 0,
                    TamanioGabinete.DESCONOCIDO, 0, ClaseDisipador.DESCONOCIDA, 0);

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

    /**
     * pc-builder-deep-taxonomy T3a-T4c shape (16 args, the record's canonical
     * constructor before T4d-2 appended {@code tipoCooler}): kept so every
     * existing reader and the T1-T4 rule tests keep compiling untouched —
     * refactor contract, CODE-2. The new field defaults to its abstention
     * value, same as EMPTY.
     */
    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion, int velocidadMhz, TipoAlmacenamiento tipoAlmacenamiento,
            List<String> socketsSoportados, String marcaChip, int generacion, int tierChipset, int modulos,
            boolean wifi) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion,
                velocidadMhz, tipoAlmacenamiento, socketsSoportados, marcaChip, generacion, tierChipset, modulos,
                wifi, TipoCooler.DESCONOCIDO);
    }

    /**
     * pc-builder-deep-taxonomy T4d-2 shape (17 args, the record's canonical
     * constructor before pc-builder-top-tier T1 appended {@code nivel}): kept
     * so every existing reader and rule test keeps compiling untouched —
     * refactor contract, CODE-2. The new field defaults to its abstention
     * value, same as EMPTY.
     */
    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion, int velocidadMhz, TipoAlmacenamiento tipoAlmacenamiento,
            List<String> socketsSoportados, String marcaChip, int generacion, int tierChipset, int modulos,
            boolean wifi, TipoCooler tipoCooler) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion,
                velocidadMhz, tipoAlmacenamiento, socketsSoportados, marcaChip, generacion, tierChipset, modulos,
                wifi, tipoCooler, 0);
    }

    /**
     * pc-builder-top-tier T1 shape (18 args, the record's canonical
     * constructor before fase 9 appended {@code tamanioGabinete}/{@code
     * radiadorMm}): kept so every existing reader and rule test keeps
     * compiling untouched — refactor contract, CODE-2. The two new fields
     * default to their abstention values, same as EMPTY.
     */
    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion, int velocidadMhz, TipoAlmacenamiento tipoAlmacenamiento,
            List<String> socketsSoportados, String marcaChip, int generacion, int tierChipset, int modulos,
            boolean wifi, TipoCooler tipoCooler, int nivel) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion,
                velocidadMhz, tipoAlmacenamiento, socketsSoportados, marcaChip, generacion, tierChipset, modulos,
                wifi, tipoCooler, nivel, TamanioGabinete.DESCONOCIDO, 0);
    }

    /**
     * fase 9 shape (20 args, the record's canonical constructor before T16
     * appended {@code claseDisipador}/{@code heatpipes}): kept so every
     * existing reader and rule test keeps compiling untouched — refactor
     * contract, CODE-2. The two new fields default to their abstention
     * values, same as EMPTY.
     */
    public TechSpecs(String socket, String ddr, String formFactor, int watts, int capacidadGb, String tipoMemoria,
            Gama gama, Certificacion certificacion, int velocidadMhz, TipoAlmacenamiento tipoAlmacenamiento,
            List<String> socketsSoportados, String marcaChip, int generacion, int tierChipset, int modulos,
            boolean wifi, TipoCooler tipoCooler, int nivel, TamanioGabinete tamanioGabinete, int radiadorMm) {
        this(socket, ddr, formFactor, watts, capacidadGb, tipoMemoria, gama, certificacion,
                velocidadMhz, tipoAlmacenamiento, socketsSoportados, marcaChip, generacion, tierChipset, modulos,
                wifi, tipoCooler, nivel, tamanioGabinete, radiadorMm, ClaseDisipador.DESCONOCIDA, 0);
    }
}
