package ar.scraper.scheduling;

import java.util.List;
import java.util.Optional;

/**
 * Persistence seam for the {@code cron_jobs} / {@code cron_executions} aggregate —
 * lets {@code ar.scraper.cron} depend on this contract instead of {@code ar.scraper.db}.
 */
public interface CronPort {

    long insertCronJob(String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled, String nextRunAt);

    boolean updateCronJob(long id, String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled, String nextRunAt);

    boolean deleteCronJob(long id);

    List<CronJob> listCronJobs();

    Optional<CronJob> getCronJob(long id);

    boolean touchLastRunAt(long jobId, String lastRunAt);

    boolean updateNextRunAt(long jobId, String nextRunAt);

    long insertCronExecution(long jobId, String startedAt, String status, String skippedReason);

    boolean updateCronExecution(long execId, String finishedAt, String status,
            String skippedReason, String logOutput, Integer durationMs);

    List<CronExecution> listExecutions(long jobId, int limit);

    Optional<CronExecution> getExecution(long execId);

    void pruneCronExecutions(long jobId, int keep);
}
