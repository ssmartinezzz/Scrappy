package ar.scraper.scrape;

import java.time.Instant;

/**
 * Reemplaza al {@code Instant runStartedAt} suelto que recibia {@code ProductPort.upsertProductos},
 * porque un reloj no puede decir que sitios miro una corrida.
 */
public record CorridaEnCurso(long runId, Instant startedAt) {}
