package ar.scraper.cron;

/**
 * Single run record for a {@link CronJob} — mirrors {@code cron_executions}
 * (DatabaseService). {@code status} is one of "running" | "success" | "error" | "skipped".
 * {@code logOutput} captures the {@code ar.scraper.run} logger output for the
 * run's window (see {@link CronJobRunner}).
 */
public record CronExecution(
        long id,
        long jobId,
        String startedAt,
        String finishedAt,
        String status,
        String skippedReason,
        String logOutput,
        Integer durationMs) {
}
