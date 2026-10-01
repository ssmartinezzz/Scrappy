package ar.scraper.scrape;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CorridaInterrumpida(
        long runId,
        UUID uuid,
        Instant startedAt,
        List<String> atendidos,
        List<String> pendientes,
        List<String> salteados) {

    /** True when every site finished and the crash landed in the final ML/aggregation pass. */
    public boolean soloFaltaLaPasadaFinal() {
        return pendientes.isEmpty();
    }
}
