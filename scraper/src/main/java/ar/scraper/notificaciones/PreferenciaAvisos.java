package ar.scraper.notificaciones;

/** What an account can say about price-drop mail; {@code tieneEmail} false means there is nothing to opt in to. */
public record PreferenciaAvisos(boolean notificarBajasPrecio, boolean tieneEmail) {
}
