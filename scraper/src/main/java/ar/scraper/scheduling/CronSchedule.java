package ar.scraper.scheduling;

import java.time.ZonedDateTime;

/** Cron-expression arithmetic behind the scheduler. Implemented in {@code ar.scraper.config}. */
public interface CronSchedule {

    /** Next fire time after {@code from}, or null when there is none. Throws {@link IllegalArgumentException} for an invalid expression. */
    ZonedDateTime nextRun(String cronExpr, ZonedDateTime from);

    boolean isValid(String cronExpr);
}
