package ar.scraper.cron;

import java.util.List;

/**
 * Cron job configuration row — mirrors {@code cron_jobs} (DatabaseService).
 * {@code sitios} vacío/[] significa "todos los sitios" (mismo criterio que la
 * selección manual de {@code /api/scrape}). Persistido por {@link CronJobService}.
 */
public record CronJob(
        long id,
        String name,
        double precioMin,
        double precioMax,
        List<String> sitios,
        boolean forceRetrain,
        boolean useGpu,
        String cronExpr,
        boolean enabled,
        String createdAt,
        String updatedAt,
        String lastRunAt,
        String nextRunAt) {
}
