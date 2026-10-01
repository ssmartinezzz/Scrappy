package ar.scraper.db;

import ar.scraper.pcs.Uso;

/**
 * {@link Uso#GAMING} has its OWN seeded row, just like {@link Uso#HOMELAB} — it is a requestable
 * value, not a sentinel.
 */
final class UsoMapeo {

    private UsoMapeo() {}

    static String nombreDeUso(Uso uso) {
        return switch (uso) {
            case GAMING -> "GAMING";
            case HOMELAB -> "HOMELAB";
        };
    }

    static Uso usoDeNombre(String nombre) {
        if (nombre == null) return Uso.GAMING;
        return switch (nombre) {
            case "GAMING" -> Uso.GAMING;
            case "HOMELAB" -> Uso.HOMELAB;
            default -> throw new IllegalStateException("uso.nombre desconocido en la base: " + nombre);
        };
    }
}
