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
 */
public final class PreferenciasWire {

    private PreferenciasWire() {}

    /** Blank/null every field parses to {@link PreferenciasDeArmado#NINGUNA}. */
    public static PreferenciasDeArmado parse(String ddrWire, String marcaCpuWire, String marcaGpuWire,
            String tipoAlmacenamientoWire, Boolean ramDual, Boolean wifi) {
        return new PreferenciasDeArmado(
                parseDdr(ddrWire), parseMarcaCpu(marcaCpuWire), parseMarcaGpu(marcaGpuWire),
                parseTipoAlmacenamiento(tipoAlmacenamientoWire), ramDual, wifi);
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
