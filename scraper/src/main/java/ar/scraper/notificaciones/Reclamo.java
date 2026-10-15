package ar.scraper.notificaciones;

/** The claim a relay holds on a row: the attempt number its claim stamped, which no later claim shares. */
public record Reclamo(long id, int intentos) {
}
