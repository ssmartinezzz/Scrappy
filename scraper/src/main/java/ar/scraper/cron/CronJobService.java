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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dueño del scheduling de cron jobs (poller + CRUD facade). El estado vive en
 * SQLite ({@code cron_jobs.next_run_at}) — no hay registro en memoria de
 * {@code ScheduledFuture}s, así que un reinicio del proceso es transparente
 * (ver ADR-1, {@code sdd/scraper-cronjobs/design}).
 *
 * <p>{@link #tick()} corre cada 30s ({@code @Scheduled(fixedDelay = 30000)}).
 * NO bloquea: cada job vencido se despacha en un hilo virtual vía
 * {@link #dispatchAsync(CronJob)}, así que {@code tick()} retorna de
 * inmediato y el único hilo scheduler de Spring queda libre para las demás
 * tareas {@code @Scheduled} (p.ej. el fetch diario de las 8am de
 * {@code InflacionService}) sin que un scraping largo las retrase.
 * {@code next_run_at} se recalcula/persiste dentro de ese mismo hilo virtual,
 * al terminar cada job (éxito, error o excepción).</p>
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

    /**
     * Versión no-throwing de {@link CronExpression#parse} — usada por
     * {@code CronApiController} para validar {@code cronExpr} en create/update
     * y devolver 400 en vez de dejar propagar un {@link IllegalArgumentException}
     * como 500. Válido independientemente de {@code enabled} (un job deshabilitado
     * puede habilitarse más adelante con la misma expresión).
     */
    public boolean isValidCronExpr(String cronExpr) {
        try {
            CronExpression.parse(cronExpr);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
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
            dispatchAsync(job);
        }
    }

    /**
     * Despacha UN job en un hilo virtual — usado tanto por {@link #tick()}
     * como por {@link #triggerNow(long)}, así ambos caminos comparten
     * exactamente el mismo comportamiento asíncrono (nada bloquea al llamador)
     * y el mismo manejo de errores/rescheduling. El caller es responsable de
     * haber agregado {@code job.id()} a {@link #inFlight} ANTES de llamar a
     * este método (el guard vive en el caller porque {@code triggerNow}
     * necesita distinguir ese caso como {@code BUSY} antes de despachar).
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

    // ── run-now (manual trigger vía REST) ───────────────────────────────────

    /** Resultado de {@link #triggerNow(long)} — mapeado 1:1 a un status HTTP en {@code CronApiController}. */
    public enum RunNowResult { NOT_FOUND, BUSY, STARTED }

    /**
     * Dispara un job MANUALMENTE (fuera del poll de 30s), para el endpoint
     * {@code POST /api/cron/{id}/run-now}. Debe ser NO BLOQUEANTE — un hilo
     * HTTP no puede esperar hasta 2h a que {@link CronJobRunner#runJob}
     * termine — así que el trabajo real se despacha en un hilo virtual y este
     * método retorna de inmediato.
     *
     * <p>A diferencia del guard RUNNING dentro de {@code runJob} (que registra
     * una ejecución "skipped" silenciosa), acá preferimos un 409 explícito
     * ANTES de despachar — por eso se chequea {@link CronJobRunner#isScraperBusy()}
     * acá, no dentro del hilo virtual.</p>
     *
     * <p>Reutiliza el MISMO {@code inFlight} que usa {@link #tick()}, así un
     * run-now manual y el poller nunca pueden despachar el mismo job dos
     * veces en simultáneo.</p>
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
