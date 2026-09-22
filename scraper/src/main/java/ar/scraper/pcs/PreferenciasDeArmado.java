package ar.scraper.pcs;

/**
 * Technical preferences the caller asked for, one field per axis — every
 * field nullable meaning "not requested" (D1, pc-builder-deep-taxonomy T4).
 * {@code ramDual}/{@code wifi} are {@link Boolean}, not {@code boolean},
 * because only {@code TRUE} means "asked": {@code FALSE} behaves exactly
 * like {@code null} — nobody asks for "no wifi" (D2's documented exception
 * for the two boolean axes).
 *
 * <p>Fase 9 adds four more: {@code capacidadMinimaGb} and {@code
 * wattsMinimos} are FLOORS ("at least"), not exact values (D3) — asking for
 * 1 TB must not drop the 2 TB, which is the better candidate. {@code
 * tamanioGabinete} and {@code tipoCooler} are exact, like the other enum
 * axes. No abstention sentinel is ever a requestable value here, same as
 * {@link TipoAlmacenamiento#DESCONOCIDO} since T4.</p>
 */
public record PreferenciasDeArmado(
        String ddr,                         // "DDR4" | "DDR5" | null — no pedida
        String marcaCpu,                    // "INTEL" | "AMD" | null
        String marcaGpu,                    // "NVIDIA" | "AMD" | null
        TipoAlmacenamiento tipoAlmacenamiento, // NVME | SSD | HDD | null — nunca DESCONOCIDO
        Boolean ramDual,                    // TRUE pide dual; FALSE == null
        Boolean wifi,                       // TRUE pide wifi; FALSE == null
        Integer capacidadMinimaGb,          // piso de GB del disco; null = no pedido (D3)
        TamanioGabinete tamanioGabinete,    // MINI | MID | FULL | null — nunca DESCONOCIDO
        TipoCooler tipoCooler,              // LIQUIDO | AIRE | null — nunca DESCONOCIDO
        Integer wattsMinimos                // piso de watts de la fuente; null = no pedido (D5)
) {
    public static final PreferenciasDeArmado NINGUNA =
            new PreferenciasDeArmado(null, null, null, null, null, null, null, null, null, null);

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
        if (tamanioGabinete == TamanioGabinete.DESCONOCIDO) {
            throw new IllegalArgumentException(
                    "tamanioGabinete no puede ser DESCONOCIDO: es un centinela de abstención, no un valor pedible");
        }
        if (tipoCooler == TipoCooler.DESCONOCIDO) {
            throw new IllegalArgumentException(
                    "tipoCooler no puede ser DESCONOCIDO: es un centinela de abstención, no un valor pedible");
        }
        // Un piso de 0 no es "pedí cero GB", es "no pediste nada" escrito mal:
        // se rechaza en vez de colarse como un filtro que no filtra.
        if (capacidadMinimaGb != null && capacidadMinimaGb <= 0) {
            throw new IllegalArgumentException("capacidadMinimaGb tiene que ser > 0: " + capacidadMinimaGb);
        }
        if (wattsMinimos != null && wattsMinimos <= 0) {
            throw new IllegalArgumentException("wattsMinimos tiene que ser > 0: " + wattsMinimos);
        }
    }

    /**
     * pc-builder-deep-taxonomy T4 shape (6 args, the record's canonical
     * constructor before fase 9): kept so every existing caller and test
     * keeps compiling untouched — refactor contract, CODE-2. The four new
     * fields default to "not requested", same as {@link #NINGUNA}.
     */
    public PreferenciasDeArmado(String ddr, String marcaCpu, String marcaGpu,
            TipoAlmacenamiento tipoAlmacenamiento, Boolean ramDual, Boolean wifi) {
        this(ddr, marcaCpu, marcaGpu, tipoAlmacenamiento, ramDual, wifi, null, null, null, null);
    }
}
