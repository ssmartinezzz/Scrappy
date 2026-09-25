package ar.scraper.db;

import ar.scraper.pcs.Uso;

/**
 * Java↔DB mapping for {@code uso_id} (V39), shared by every repository that
 * writes or reads it — same molde as {@link GamaMapeo} (CODE-6). Unlike
 * {@code gama_id} (NOT NULL: every row states one), {@code uso_id} is
 * nullable, but NULL is never written on purpose here — it is only ever
 * inherited from a row saved before this migration existed ("no uso chosen
 * yet"), a real abstention, D10. Every save from {@code UsoMapeo} onward
 * points at a concrete row: {@link Uso#GAMING} has its OWN seeded row, just
 * like {@link Uso#HOMELAB} — it is a requestable value, not a sentinel.
 */
final class UsoMapeo {

    private UsoMapeo() {}

    static String nombreDeUso(Uso uso) {
        return switch (uso) {
            case GAMING -> "GAMING";
            case HOMELAB -> "HOMELAB";
        };
    }

    /** {@code null} (never chosen, a pre-V39 row) reads back as {@link Uso#GAMING} — same default as always. */
    static Uso usoDeNombre(String nombre) {
        if (nombre == null) return Uso.GAMING;
        return switch (nombre) {
            case "GAMING" -> Uso.GAMING;
            case "HOMELAB" -> Uso.HOMELAB;
            default -> throw new IllegalStateException("uso.nombre desconocido en la base: " + nombre);
        };
    }
}
