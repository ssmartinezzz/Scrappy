package ar.scraper.db;

import ar.scraper.pcs.Gama;

/**
 * Java↔DB mapping for {@code gama} (V35), shared by every repository that
 * writes or reads a {@code gama_id} column — extracted from
 * {@code PreferenciaArmadorRepository} so {@code TechSpecsRepository}
 * doesn't grow a second copy of {@code Gama.BAJA ↔ 'ECONOMICA'} (CODE-6).
 */
final class GamaMapeo {

    private GamaMapeo() {}

    /** {@code Gama.BAJA} maps to the {@code ECONOMICA} row — that row is the DB vocabulary. */
    static String nombreDeGama(Gama gama) {
        return switch (gama) {
            case BAJA -> "ECONOMICA";
            case MEDIA -> "MEDIA";
            case ALTA -> "ALTA";
            case DESCONOCIDA -> throw new IllegalArgumentException(
                    "Gama.DESCONOCIDA es un centinela de abstención, nunca un valor de FK (D10)");
        };
    }

    static Gama gamaDeNombre(String nombre) {
        return switch (nombre) {
            case "ECONOMICA" -> Gama.BAJA;
            case "MEDIA" -> Gama.MEDIA;
            case "ALTA" -> Gama.ALTA;
            default -> throw new IllegalStateException("gama.nombre desconocido en la base: " + nombre);
        };
    }
}
