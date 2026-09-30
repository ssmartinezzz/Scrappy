package ar.scraper.pcs;

import org.apache.commons.lang3.StringUtils;

/**
 * {@code tipoAlmacenamiento}'s wire word for {@link TipoAlmacenamiento#SSD} is {@code "sata"}, not
 * {@code "ssd"} — the wire vocabulary names the interface a user shops by, not the enum's own Java
 * name.
 */
public final class PreferenciasWire {

    private PreferenciasWire() {}

    public static PreferenciasDeArmado parse(String ddrWire, String marcaCpuWire, String marcaGpuWire,
            String tipoAlmacenamientoWire, Boolean ramDual, Boolean wifi) {
        return parse(ddrWire, marcaCpuWire, marcaGpuWire, tipoAlmacenamientoWire, ramDual, wifi,
                null, null, null, null);
    }

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
        if (StringUtils.isBlank(wire)) return null;
        return switch (wire.trim().toLowerCase()) {
            case "mini" -> TamanioGabinete.MINI;
            case "mid" -> TamanioGabinete.MID;
            case "full" -> TamanioGabinete.FULL;
            default -> throw new IllegalArgumentException("tamanioGabinete inválido: " + wire);
        };
    }

    public static TipoCooler parseTipoCooler(String wire) {
        if (StringUtils.isBlank(wire)) return null;
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
        if (StringUtils.isBlank(wire)) return null;
        String v = wire.trim().toUpperCase();
        if (v.equals("DDR4") || v.equals("DDR5")) return v;
        throw new IllegalArgumentException("ddr inválida: " + wire);
    }

    public static String parseMarcaCpu(String wire) {
        if (StringUtils.isBlank(wire)) return null;
        String v = wire.trim().toUpperCase();
        if (v.equals("INTEL") || v.equals("AMD")) return v;
        throw new IllegalArgumentException("marcaCpu inválida: " + wire);
    }

    public static String parseMarcaGpu(String wire) {
        if (StringUtils.isBlank(wire)) return null;
        String v = wire.trim().toUpperCase();
        if (v.equals("NVIDIA") || v.equals("AMD")) return v;
        throw new IllegalArgumentException("marcaGpu inválida: " + wire);
    }

    public static TipoAlmacenamiento parseTipoAlmacenamiento(String wire) {
        if (StringUtils.isBlank(wire)) return null;
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
