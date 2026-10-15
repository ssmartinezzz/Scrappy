package ar.scraper.notificaciones;

import java.util.UUID;

/**
 * One claimed outbox row, joined with what the mail needs: the recipient and the product.
 * {@code intentos} is the attempt counter as the claim left it; it is the token that proves
 * the row is still this claimer's when the outcome is recorded.
 */
public record BajaReclamada(long id, UUID usuarioId, String email, String url, String nombre,
                            String sitio, double precioAnterior, double precioNuevo, int intentos) {

    public Reclamo reclamo() {
        return new Reclamo(id, intentos);
    }
}
