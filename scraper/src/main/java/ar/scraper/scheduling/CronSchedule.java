package ar.scraper.scheduling;

import java.time.ZonedDateTime;

public interface CronSchedule {

    /** Next fire time after {@code from}, or null when there is none. */
    ZonedDateTime nextRun(String cronExpr, ZonedDateTime from);

    boolean isValid(String cronExpr);
}
