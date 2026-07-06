package ar.scraper.cron;

import ar.scraper.db.DatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dueño del scheduling de cron jobs (poller + CRUD facade). El estado vive en
 * SQLite ({@code cron_jobs.next_run_at}) — no hay registro en memoria de
 * {@code ScheduledFuture}s, así que un reinicio del proceso es transparente
 * (ver ADR-1, {@code sdd/scraper-cronjobs/design}).
 *
 * <p>{@link #tick()} corre cada 30s ({@code @Scheduled(fixedDelay = 30000)}),
 * dispara los jobs vencidos vía {@link CronJobRunner#runJob(CronJob)} y
 * recalcula/persiste {@code next_run_at} al terminar cada uno (éxito o no).</p>
 */
@Service
public class CronJobService {

    private static final Logger LOG = LoggerFactory.getLogger(CronJobService.class);

    /**
     * ISO local date-time SIEMPRE con segundos (a diferencia de
     * {@code LocalDateTime.toString()}, que los omite cuando son {@code :00}) —
     * necesario para que {@code nextRunAt}/{@code lastRunAt} sean consistentes
     * y parseables por {@link LocalDateTime#parse(CharSequence)}.
     */
    static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final DatabaseService db;
    private final CronJobRunner runner;
    private final Clock clock;

    /** Evita disparar el mismo job dos veces si un tick tarda más que el intervalo. */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public CronJobService(DatabaseService db, CronJobRunner runner, Clock clock) {
        this.db = db;
        this.runner = runner;
        this.clock = clock;
    }

    // ── nextRunAt (pure) ─────────────────────────────────────────────────────

    /**
     * Calcula el próximo disparo ISO ({@code LocalDateTime.toString()}) para
     * {@code cronExpr} (formato Spring de 6 campos, igual que
     * {@code @Scheduled(cron=...)}) a partir de {@code from}. Lanza
     * {@link IllegalArgumentException} si {@code cronExpr} es inválido
     * (comportamiento nativo de {@link CronExpression#parse}).
     */
    public String computeNextRun(String cronExpr, ZonedDateTime from) {
        CronExpression expr = CronExpression.parse(cronExpr);
        ZonedDateTime next = expr.next(from);
        return next != null ? next.toLocalDateTime().format(ISO_SECONDS) : null;
    }

    // ── CRUD facade ──────────────────────────────────────────────────────────

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

    // ── Poller ───────────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30_000)
    public void tick() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        for (CronJob job : dueJobs(db.listCronJobs(), now)) {
            if (!inFlight.add(job.id())) continue; // ya en curso, no disparar dos veces
            try {
                runner.runJob(job);
            } catch (Exception e) {
                LOG.warn("[CRON] Job {} ({}) falló en tick(): {}", job.id(), job.name(), e.getMessage());
            } finally {
                String next = computeNextRun(job.cronExpr(), ZonedDateTime.now(clock));
                db.updateNextRunAt(job.id(), next);
                inFlight.remove(job.id());
            }
        }
    }

    /**
     * Filtra los jobs {@code enabled} cuyo {@code nextRunAt} sea nulo (nunca
     * calculado) o ya haya pasado. Extraído como método puro para poder
     * testear la lógica de disparo sin contexto de Spring.
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

    private ZonedDateTime parseAsZoned(String iso, ZoneId zone) {
        return LocalDateTime.parse(iso).atZone(zone);
    }
}
