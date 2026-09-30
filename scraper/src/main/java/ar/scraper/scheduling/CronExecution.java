package ar.scraper.scheduling;

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
