package ar.scraper.notificaciones;

/** A rendered mail: the HTML body and its plain-text alternative. */
public record CorreoRenderizado(String asunto, String html, String texto) {
}
