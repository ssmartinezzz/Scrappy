package ar.scraper.scheduling;

import java.util.List;

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
