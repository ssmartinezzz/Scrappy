package ar.scraper.pcs;

/**
 * Wire↔domain mapping for {@code uso} ("gaming"/"homelab") — same molde as
 * {@link GamaWire} (CODE-6/DOC-1), shared by the builder endpoint, the
 * preferencia endpoints and {@code agent.ProposePcTool}.
 *
 * <p>Unlike {@link GamaWire#parse}, blank/null does NOT mean "not
 * requested" — {@link Uso} has no such state. It means the default,
 * {@link Uso#GAMING}: today's build, unchanged.</p>
 */
public final class UsoWire {

    private UsoWire() {}

    /** Blank/null parses to {@link Uso#GAMING} (the default). Any other non-blank value throws. */
    public static Uso parse(String valorWire) {
        if (valorWire == null || valorWire.isBlank()) return Uso.GAMING;
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
