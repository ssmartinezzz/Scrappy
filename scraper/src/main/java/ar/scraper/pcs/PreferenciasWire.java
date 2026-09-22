package ar.scraper.pcs;

/**
 * Wire↔domain mapping for the six technical preferences from
 * pc-builder-deep-taxonomy (D8) — shared by the builder endpoint, the
 * preferencia endpoints and {@code agent.ProposePcTool}, same molde as
 * {@link GamaWire} (CODE-6/DOC-1): parse + wire, never emits an abstention
 * sentinel ({@code TipoAlmacenamiento.DESCONOCIDO}) as if it were a
 * requestable value.
 *
 * <p>{@code tipoAlmacenamiento}'s wire word for {@link TipoAlmacenamiento#SSD}
 * is {@code "sata"}, not {@code "ssd"} — the wire vocabulary names the
 * interface a user shops by, not the enum's own Java name.</p>
 *
 * <p>Fase 9 adds four more. The two floors ({@code capacidadMinimaGb},
 * {@code wattsMinimos}) travel as plain integers — there is no enum to name,
 * and the UI's chips are just convenient values, never a closed domain the
 * server enforces. The two enums keep the same shape as the others: a
 * lowercase word, and never an abstention sentinel.</p>
 */
public final class PreferenciasWire {

    private PreferenciasWire() {}

    /**
     * Pre-fase-9 shape: none of the four new preferences requested. Blank/null
     * every field parses to {@link PreferenciasDeArmado#NINGUNA}.
     */
    public static PreferenciasDeArmado parse(String ddrWire, String marcaCpuWire, String marcaGpuWire,
            String tipoAlmacenamientoWire, Boolean ramDual, Boolean wifi) {
        return parse(ddrWire, marcaCpuWire, marcaGpuWire, tipoAlmacenamientoWire, ramDual, wifi,
                null, null, null, null);
    }

    /** Fase 9: the six above plus capacidad/tamaño/cooler/watts. */
    public static PreferenciasDeArmado parse(String ddrWire, String marcaCpuWire, String marcaGpuWire,
            String tipoAlmacenamientoWire, Boolean ramDual, Boolean wifi,
            Integer capacidadMinimaGb, String tamanioGabineteWire, String tipoCoolerWire, Integer wattsMinimos) {
        return new PreferenciasDeArmado(
                parseDdr(ddrWire), parseMarcaCpu(marcaCpuWire), parseMarcaGpu(marcaGpuWire),
                parseTipoAlmacenamiento(tipoAlmacenamientoWire), ramDual, wifi,
                capacidadMinimaGb, parseTamanioGabinete(tamanioGabineteWire),
                parseTipoCooler(tipoCoolerWire), wattsMinimos);
    }

    public static TamanioGabinete parseTamanioGabinete(String wire) {
        if (wire == null || wire.isBlank()) return null;
        return switch (wire.trim().toLowerCase()) {
            case "mini" -> TamanioGabinete.MINI;
            case "mid" -> TamanioGabinete.MID;
            case "full" -> TamanioGabinete.FULL;
            default -> throw new IllegalArgumentException("tamanioGabinete inválido: " + wire);
        };
    }

    public static TipoCooler parseTipoCooler(String wire) {
        if (wire == null || wire.isBlank()) return null;
        return switch (wire.trim().toLowerCase()) {
            case "liquido" -> TipoCooler.LIQUIDO;
            case "aire" -> TipoCooler.AIRE;
            default -> throw new IllegalArgumentException("tipoCooler inválido: " + wire);
        };
    }

    public static String wireTamanioGabinete(TamanioGabinete tamanio) {
        if (tamanio == null) return null;
        return switch (tamanio) {
            case MINI -> "mini";
            case MID -> "mid";
            case FULL -> "full";
            case DESCONOCIDO -> throw new IllegalArgumentException(
                    "TamanioGabinete.DESCONOCIDO es un centinela de abstención, nunca un valor de borde");
        };
    }

    public static String wireTipoCooler(TipoCooler tipo) {
        if (tipo == null) return null;
        return switch (tipo) {
            case LIQUIDO -> "liquido";
            case AIRE -> "aire";
            case DESCONOCIDO -> throw new IllegalArgumentException(
                    "TipoCooler.DESCONOCIDO es un centinela de abstención, nunca un valor de borde");
        };
    }

    public static String parseDdr(String wire) {
        if (wire == null || wire.isBlank()) return null;
        String v = wire.trim().toUpperCase();
        if (v.equals("DDR4") || v.equals("DDR5")) return v;
        throw new IllegalArgumentException("ddr inválida: " + wire);
    }

    public static String parseMarcaCpu(String wire) {
        if (wire == null || wire.isBlank()) return null;
        String v = wire.trim().toUpperCase();
        if (v.equals("INTEL") || v.equals("AMD")) return v;
        throw new IllegalArgumentException("marcaCpu inválida: " + wire);
    }

    public static String parseMarcaGpu(String wire) {
        if (wire == null || wire.isBlank()) return null;
        String v = wire.trim().toUpperCase();
        if (v.equals("NVIDIA") || v.equals("AMD")) return v;
        throw new IllegalArgumentException("marcaGpu inválida: " + wire);
    }

    public static TipoAlmacenamiento parseTipoAlmacenamiento(String wire) {
        if (wire == null || wire.isBlank()) return null;
        return switch (wire.trim().toLowerCase()) {
            case "nvme" -> TipoAlmacenamiento.NVME;
            case "sata" -> TipoAlmacenamiento.SSD;
            case "hdd" -> TipoAlmacenamiento.HDD;
            default -> throw new IllegalArgumentException("tipoAlmacenamiento inválida: " + wire);
        };
    }

    public static String wireDdr(String ddr) {
        return ddr == null ? null : ddr.toLowerCase();
    }

    public static String wireMarcaCpu(String marcaCpu) {
        return marcaCpu == null ? null : marcaCpu.toLowerCase();
    }

    public static String wireMarcaGpu(String marcaGpu) {
        return marcaGpu == null ? null : marcaGpu.toLowerCase();
    }

    public static String wireTipoAlmacenamiento(TipoAlmacenamiento tipo) {
        if (tipo == null) return null;
        return switch (tipo) {
            case NVME -> "nvme";
            case SSD -> "sata";
            case HDD -> "hdd";
            case DESCONOCIDO -> throw new IllegalArgumentException(
                    "TipoAlmacenamiento.DESCONOCIDO es un centinela de abstención, nunca un valor de borde");
        };
    }
}
