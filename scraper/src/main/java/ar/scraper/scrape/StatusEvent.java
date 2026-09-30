package ar.scraper.scrape;

import java.util.List;

/** Something the UI shows that just changed. Plain data: no framework types cross this port. */
public sealed interface StatusEvent {

    /** Status line of the scrape run, as served by {@code /api/status}. */
    record ScrapeStatus(ScraperStatus status, String message) implements StatusEvent {}

    /** Per-site progress of the run in flight. */
    record ScrapeProgress(int total, int completados, int productos, List<SiteProgress> sitios)
            implements StatusEvent {}

    record SiteProgress(String nombre, String estado, int productos, String error, long duracionMs) {}

    /** Training or visual-attribute backfill, as served by {@code /api/ml/estado}. */
    record MlStatus(Kind kind, boolean running, String phase, int pct, String message, String startedAt)
            implements StatusEvent {
        public enum Kind { TRAINING, BACKFILL }
    }

    /**
     * A row of {@code scrape_run}, {@code scrape_run_site} or {@code cron_executions} changed
     * status, announced by the database itself. Fields that do not apply to a table are null.
     */
    record DbChanged(String table, String op, Long id, Long run, String site, Long job, String status)
            implements StatusEvent {}

    /** Events may have been lost (the listener reconnected, a client fell behind): re-read everything. */
    record Resync() implements StatusEvent {}
}
