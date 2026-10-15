package ar.scraper.notificaciones;

import java.util.UUID;

/** One claimed outbox row, joined with what the mail needs: the recipient and the product. */
public record BajaReclamada(long id, UUID usuarioId, String email, String url, String nombre,
                            String sitio, double precioAnterior, double precioNuevo) {
}
