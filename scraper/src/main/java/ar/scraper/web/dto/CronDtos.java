package ar.scraper.web.dto;

import ar.scraper.scheduling.CronExecution;
import ar.scraper.scheduling.CronJob;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** Payloads of the cron job endpoints. */
public final class CronDtos {

    private CronDtos() {}

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Job {
        private long id;
        private String name;
        private double precioMin;
        private double precioMax;
        private List<String> sitios;
        private boolean forceRetrain;
        private boolean useGpu;
        private String cronExpr;
        private boolean enabled;
        private String createdAt;
        private String updatedAt;
        private String lastRunAt;
        private String nextRunAt;

        public static Job from(CronJob j) {
            return new Job(j.id(), j.name(), j.precioMin(), j.precioMax(), j.sitios(), j.forceRetrain(),
                    j.useGpu(), j.cronExpr(), j.enabled(), j.createdAt(), j.updatedAt(), j.lastRunAt(),
                    j.nextRunAt());
        }
    }

    /**
     * {@code status}/{@code skippedReason} describe the run and are data, not an API error;
     * {@code logOutput} is listed here because there is no single-execution endpoint.
     */
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Execution {
        private long id;
        private long jobId;
        private String startedAt;
        private String finishedAt;
        private String status;
        private String skippedReason;
        private Integer durationMs;
        private String logOutput;

        public static Execution from(CronExecution e) {
            return new Execution(e.id(), e.jobId(), e.startedAt(), e.finishedAt(), e.status(),
                    e.skippedReason(), e.durationMs(), e.logOutput());
        }
    }
}
