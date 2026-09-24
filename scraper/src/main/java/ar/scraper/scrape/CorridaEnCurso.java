package ar.scraper.scrape;

import java.time.Instant;

/**
 * La corrida por cuenta de la cual se escribe: identidad y arranque, juntos.
 *
 * <p>Reemplaza al {@code Instant runStartedAt} suelto que recibia
 * {@code ProductPort.upsertProductos}, porque un reloj no puede decir que
 * sitios miro una corrida. El {@code started_at} de una corrida retomada puede
 * ser de hace dias: la corrida 16 arranco el 22 a las 16:49 y se retomo el 24 a
 * las 14:54, con cinco corridas en el medio, asi que su ventana nombraba los 28
 * sitios del catalogo para una corrida que habia mirado cinco (2026-09-24).</p>
 *
 * @param startedAt ya truncado al segundo por {@code ScrapeRunRepository.crear}
 */
public record CorridaEnCurso(long runId, Instant startedAt) {}
