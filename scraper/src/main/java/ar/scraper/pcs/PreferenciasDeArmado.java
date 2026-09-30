package ar.scraper.pcs;

/**
 * Technical preferences the caller asked for, one field per axis — every field nullable meaning
 * "not requested". {@code ramDual}/{@code wifi} are {@link Boolean}, not {@code boolean}, because
 * only {@code TRUE} means "asked":
 */
public record PreferenciasDeArmado(
        String ddr,
        String marcaCpu,
        String marcaGpu,
        TipoAlmacenamiento tipoAlmacenamiento, // NVME | SSD | HDD | null — nunca DESCONOCIDO
        Boolean ramDual,
        Boolean wifi,
        Integer capacidadMinimaGb,
        TamanioGabinete tamanioGabinete,    // MINI | MID | FULL | null — nunca DESCONOCIDO
        TipoCooler tipoCooler,              // LIQUIDO | AIRE | null — nunca DESCONOCIDO
        Integer wattsMinimos
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
        // Un piso de 0 no es "pedí cero GB", es "no pediste nada" escrito mal: se rechaza en vez de
        // colarse como un filtro que no filtra.
        if (capacidadMinimaGb != null && capacidadMinimaGb <= 0) {
            throw new IllegalArgumentException("capacidadMinimaGb tiene que ser > 0: " + capacidadMinimaGb);
        }
        if (wattsMinimos != null && wattsMinimos <= 0) {
            throw new IllegalArgumentException("wattsMinimos tiene que ser > 0: " + wattsMinimos);
        }
    }

    /** The four new fields default to "not requested", same as {@link #NINGUNA}. */
    public PreferenciasDeArmado(String ddr, String marcaCpu, String marcaGpu,
            TipoAlmacenamiento tipoAlmacenamiento, Boolean ramDual, Boolean wifi) {
        this(ddr, marcaCpu, marcaGpu, tipoAlmacenamiento, ramDual, wifi, null, null, null, null);
    }
}
