package ar.scraper.db;

import ar.scraper.pcs.Gama;

final class GamaMapeo {

    private GamaMapeo() {}

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
