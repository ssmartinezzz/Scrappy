package ar.scraper.pcs;

import ar.scraper.aggregator.text.AccentStripper;
import org.apache.commons.lang3.StringUtils;

/**
 * Wire↔domain mapping for the {@code gama} value that travels over HTTP and the agent tool schema
 * ("economica"/"media"/"alta") — shared by the builder endpoint, the preferencia endpoints and
 * {@code agent.ProposePcTool} so the three don't grow their own copy.
 */
public final class GamaWire {

    private GamaWire() {}

    /**
     * Parses a wire gama value, case- and accent-insensitive ({@code "económica" == "economica"}).
     */
    public static Gama parse(String valorWire) {
        if (StringUtils.isBlank(valorWire)) return null;
        String normalizado = AccentStripper.strip(valorWire.trim().toLowerCase());
        return switch (normalizado) {
            case "economica" -> Gama.BAJA;
            case "media" -> Gama.MEDIA;
            case "alta" -> Gama.ALTA;
            default -> throw new IllegalArgumentException("gama inválida: " + valorWire);
        };
    }

    /** Never called with {@link Gama#DESCONOCIDA}. */
    public static String wire(Gama gama) {
        return switch (gama) {
            case BAJA -> "economica";
            case MEDIA -> "media";
            case ALTA -> "alta";
            case DESCONOCIDA -> throw new IllegalArgumentException(
                    "Gama.DESCONOCIDA es un centinela de abstención, nunca un valor de borde");
        };
    }
}
