package ar.scraper.pcs;

/**
 * Technical preferences the caller asked for, one field per axis — every
 * field nullable meaning "not requested" (D1, pc-builder-deep-taxonomy T4).
 * {@code ramDual}/{@code wifi} are {@link Boolean}, not {@code boolean},
 * because only {@code TRUE} means "asked": {@code FALSE} behaves exactly
 * like {@code null} — nobody asks for "no wifi" (D2's documented exception
 * for the two boolean axes).
 */
public record PreferenciasDeArmado(
        String ddr,                         // "DDR4" | "DDR5" | null — no pedida
        String marcaCpu,                    // "INTEL" | "AMD" | null
        String marcaGpu,                    // "NVIDIA" | "AMD" | null
        TipoAlmacenamiento tipoAlmacenamiento, // NVME | SSD | HDD | null — nunca DESCONOCIDO
        Boolean ramDual,                    // TRUE pide dual; FALSE == null
        Boolean wifi                        // TRUE pide wifi; FALSE == null
) {
    public static final PreferenciasDeArmado NINGUNA =
            new PreferenciasDeArmado(null, null, null, null, null, null);

    public PreferenciasDeArmado {
        if (ddr != null && !ddr.equals("DDR4") && !ddr.equals("DDR5")) {
            throw new IllegalArgumentException("ddr invalida: " + ddr);
        }
        if (marcaCpu != null && !marcaCpu.equals("INTEL") && !marcaCpu.equals("AMD")) {
            throw new IllegalArgumentException("marcaCpu invalida: " + marcaCpu);
        }
        if (marcaGpu != null && !marcaGpu.equals("NVIDIA") && !marcaGpu.equals("AMD")) {
            throw new IllegalArgumentException("marcaGpu invalida: " + marcaGpu);
        }
        if (tipoAlmacenamiento == TipoAlmacenamiento.DESCONOCIDO) {
            throw new IllegalArgumentException(
                    "tipoAlmacenamiento no puede ser DESCONOCIDO: es un centinela de abstención, no un valor pedible");
        }
    }
}
