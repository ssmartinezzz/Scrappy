package ar.scraper.pcs;

import ar.scraper.aggregator.text.AccentStripper;

/**
 * Wire↔domain mapping for the {@code gama} value that travels over HTTP and
 * the agent tool schema ("economica"/"media"/"alta") — shared by the builder
 * endpoint, the preferencia endpoints and {@code agent.ProposePcTool} so the
 * three don't grow their own copy (CODE-6/DOC-1, pc-builder-gama T6).
 *
 * <p>Separate from {@code db.GamaMapeo}, which maps to the DB lookup
 * vocabulary ({@code 'ECONOMICA'}/{@code 'MEDIA'}/{@code 'ALTA'}) — a
 * different failure mode: an invalid wire value is a client error (400), an
 * unrecognized DB row is a bug ({@code IllegalStateException}).</p>
 */
public final class GamaWire {

    private GamaWire() {}

    /**
     * Parses a wire gama value, case- and accent-insensitive
     * ({@code "económica" == "economica"}). Blank/null means "no gama
     * requested" and returns {@code null} — never {@link Gama#DESCONOCIDA},
     * which is a per-candidate parser abstention, not something a caller can
     * ask for (D10). Any other non-blank value throws {@link
     * IllegalArgumentException} — callers map that to a 400.
     */
    public static Gama parse(String valorWire) {
        if (valorWire == null || valorWire.isBlank()) return null;
        String normalizado = AccentStripper.strip(valorWire.trim().toLowerCase());
        return switch (normalizado) {
            case "economica" -> Gama.BAJA;
            case "media" -> Gama.MEDIA;
            case "alta" -> Gama.ALTA;
            default -> throw new IllegalArgumentException("gama inválida: " + valorWire);
        };
    }

    /** The wire spelling for a known gama. Never called with {@link Gama#DESCONOCIDA}. */
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
