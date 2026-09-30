package ar.scraper.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * El estado vive en SQLite ({@code cron_jobs.next_run_at}) — no hay registro en memoria de
 * {@code ScheduledFuture}s, así que un reinicio del proceso es transparente.
 */
public class CronJobService {

    private static final Logger LOG = LoggerFactory.getLogger(CronJobService.class);

    /**
     * ISO local date-time SIEMPRE con segundos (a diferencia de {@code LocalDateTime.toString()},
     * que los omite cuando son {@code:00}) — necesario para que {@code nextRunAt}/{@code lastRunAt}
     * sean consistentes y parseables por {@link LocalDateTime#parse(CharSequence)}.
     */
    static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final CronPort db;
    private final CronJobRunner runner;
    private final Clock clock;
    private final CronSchedule schedule;

    /** Evita disparar el mismo job dos veces si un tick tarda más que el intervalo. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public CronJobService(CronPort db, CronJobRunner runner, Clock clock, CronSchedule schedule) {
        this.db = db;
        this.runner = runner;
        this.clock = clock;
        this.schedule = schedule;
    }

    public String computeNextRun(String cronExpr, ZonedDateTime from) {
        ZonedDateTime next = schedule.nextRun(cronExpr, from);
        return next != null ? next.toLocalDateTime().format(ISO_SECONDS) : null;
    }

    public boolean isValidCronExpr(String cronExpr) {
        return schedule.isValid(cronExpr);
    }

    public long createJob(String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled) {
        String nextRunAt = enabled ? computeNextRun(cronExpr, ZonedDateTime.now(clock)) : null;
        return db.insertCronJob(name, precioMin, precioMax, sitios, forceRetrain, useGpu,
                cronExpr, enabled, nextRunAt);
    }

    public boolean updateJob(long id, String name, double precioMin, double precioMax, List<String> sitios,
            boolean forceRetrain, boolean useGpu, String cronExpr, boolean enabled) {
        String nextRunAt = enabled ? computeNextRun(cronExpr, ZonedDateTime.now(clock)) : null;
        return db.updateCronJob(id, name, precioMin, precioMax, sitios, forceRetrain, useGpu,
                cronExpr, enabled, nextRunAt);
    }

    public void tick() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        for (CronJob job : dueJobs(db.listCronJobs(), now)) {
            if (!inFlight.add(job.id())) continue;
            dispatchAsync(job);
        }
    }

    /**
     * Despacha UN job en un hilo virtual — usado tanto por {@link #tick()} como por
     * {@link #triggerNow(long)}, así ambos caminos comparten exactamente el mismo comportamiento
     * asíncrono (nada bloquea al llamador) y el mismo manejo de errores/rescheduling.
     */
    private void dispatchAsync(CronJob job) {
        Thread.ofVirtual().start(() -> {
            try {
                runner.runJob(job);
            } catch (Exception e) {
                LOG.warn("[CRON] Job {} ({}) falló: {}", job.id(), job.name(), e.getMessage());
            } finally {
                db.updateNextRunAt(job.id(), computeNextRun(job.cronExpr(), ZonedDateTime.now(clock)));
                inFlight.remove(job.id());
            }
        });
    }

    public enum RunNowResult { NOT_FOUND, BUSY, STARTED }

    /**
     * Debe ser NO BLOQUEANTE — un hilo HTTP no puede esperar hasta 2h a que
     * {@link CronJobRunner#runJob} termine — así que el trabajo real se despacha en un hilo virtual
     * y este método retorna de inmediato.
     */
    public RunNowResult triggerNow(long id) {
        Optional<CronJob> maybeJob = db.getCronJob(id);
        if (maybeJob.isEmpty()) return RunNowResult.NOT_FOUND;
        if (runner.isScraperBusy()) return RunNowResult.BUSY;
        if (!inFlight.add(id)) return RunNowResult.BUSY;

        dispatchAsync(maybeJob.get());
        return RunNowResult.STARTED;
    }

    /**
     * Filtra los jobs {@code enabled} cuyo {@code nextRunAt} sea nulo (nunca calculado) o ya haya
     * pasado.
     */
    List<CronJob> dueJobs(List<CronJob> jobs, ZonedDateTime now) {
        ZoneId zone = now.getZone();
        List<CronJob> due = new ArrayList<>();
        for (CronJob job : jobs) {
            if (!job.enabled()) continue;
            if (job.nextRunAt() == null || !parseAsZoned(job.nextRunAt(), zone).isAfter(now)) {
                due.add(job);
            }
        }
        return due;
    }

    /**
     * Sin offset ({@code 2026-07-05T03:00:00}) cuando viene recién salido de
     * {@link #computeNextRun}, que nombra una hora LOCAL. Es la forma que se ESCRIBE, no la que se
     * lee.
     */
    private ZonedDateTime parseAsZoned(String iso, ZoneId zone) {
        try {
            return OffsetDateTime.parse(iso).atZoneSameInstant(zone);
        } catch (DateTimeParseException sinOffset) {
            return LocalDateTime.parse(iso).atZone(zone);
        }
    }
}
