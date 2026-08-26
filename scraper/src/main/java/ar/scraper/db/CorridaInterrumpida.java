package ar.scraper.db;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A scrape run a dead process left open, with its sites already split by what
 * the resume owes them.
 *
 * <p>Its own public type rather than a record nested in
 * {@code ScrapeRunRepository}, which is package-private like every repository
 * here — the same reason {@code CatalogPage} and {@code CatalogResumen} live on
 * their own. {@code ar.scraper.web} has to be able to name it.</p>
 *
 * @param atendidos  sites that had their turn — {@code DONE} or {@code ERROR}.
 *                   An {@code ERROR} site already retried three times inside the
 *                   run, so offering it again is offering to repeat a failure.
 * @param pendientes sites still owed: never started, or caught mid-scrape, whose
 *                   partial result died with the process.
 * @param salteados  sites that left the registry between the crash and the
 *                   restart. Named rather than silently dropped: a site
 *                   disappearing from a run that owed it is exactly the kind of
 *                   thing an operator needs told.
 */
public record CorridaInterrumpida(
        long runId,
        UUID uuid,
        Instant startedAt,
        List<String> atendidos,
        List<String> pendientes,
        List<String> salteados) {

    /**
     * True when every site finished and the crash landed in the final
     * ML/aggregation pass.
     *
     * <p>This is the case that gets forgotten, and the one where re-scraping is
     * pure wasted work: the run owes only its final sweep.</p>
     */
    public boolean soloFaltaLaPasadaFinal() {
        return pendientes.isEmpty();
    }
}
