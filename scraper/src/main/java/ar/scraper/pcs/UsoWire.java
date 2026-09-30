package ar.scraper.pcs;

import org.apache.commons.lang3.StringUtils;

/**
 * Unlike {@link GamaWire#parse}, blank/null does NOT mean "not requested" — {@link Uso} has no such
 * state.
 */
public final class UsoWire {

    private UsoWire() {}

    public static Uso parse(String valorWire) {
        if (StringUtils.isBlank(valorWire)) return Uso.GAMING;
        return switch (valorWire.trim().toLowerCase()) {
            case "gaming" -> Uso.GAMING;
            case "homelab" -> Uso.HOMELAB;
            default -> throw new IllegalArgumentException("uso inválido: " + valorWire);
        };
    }

    public static String wire(Uso uso) {
        return switch (uso) {
            case GAMING -> "gaming";
            case HOMELAB -> "homelab";
        };
    }
}
