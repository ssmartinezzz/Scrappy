package ar.scraper.scheduling;

import ar.scraper.scrape.ScrapeControlPort;
import ar.scraper.scrape.ScraperStatus;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

/**
 * Ejecuta UN {@link CronJob} de punta a punta, replicando la receta de
 * {@code /api/scrape} (ver {@code ApiController.scrape}, ~línea 127-146):
 * guard RUNNING (skip si ya hay un scraping en curso), captura/aplicación/
 * restauración del rango de precio y del flag GPU (decisiones 5 y ADR-2 de
 * {@code sdd/scraper-cronjobs/design}), disparo de
 * {@link ScrapeControlPort#iniciar} SIN CAMBIOS, espera bloqueante
 * acotada hasta que el scraping termine, captura del logger
 * {@code ar.scraper.run} para esa ventana, y registro/retención de la
 * ejecución en {@code cron_executions}.
 */
public class CronJobRunner {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(CronJobRunner.class);
    private static final String RUN_LOGGER = "ar.scraper.run";
    private static final int KEEP_EXECUTIONS = 50;
    private static final long POLL_INTERVAL_MS = 5_000L;
    private static final long MAX_WAIT_MS = 2L * 60 * 60 * 1000; // 2h, cota generosa
    private static final DateTimeFormatter ISO_SECONDS = CronJobService.ISO_SECONDS;

    private final ScrapeControlPort scrape;
    private final CronPort db;
    private final Clock clock;
    private final RunLogCapture logCapture;

    public CronJobRunner(ScrapeControlPort scrape, CronPort db, Clock clock) {
        this(scrape, db, clock, RunLogCapture.NONE);
    }

    public CronJobRunner(ScrapeControlPort scrape, CronPort db, Clock clock, RunLogCapture logCapture) {
        this.scrape = scrape;
        this.db = db;
        this.clock = clock;
        this.logCapture = logCapture;
    }

    /**
     * Expone el guard RUNNING para que {@code CronJobService.triggerNow}
     * (run-now manual vía REST) pueda devolver un 409 limpio ANTES de
     * despachar, en vez de dejar que {@link #runJob} registre una ejecución
     * "skipped" silenciosa. Mantiene la dependencia de {@link ScrapeControlPort}
     * donde ya vive (este runner), en vez de duplicarla en el service.
     */
    public boolean isScraperBusy() {
        return scrape.estado() == ScraperStatus.RUNNING;
    }

    public void runJob(CronJob job) {
        String now = LocalDateTime.now(clock).format(ISO_SECONDS);

        // Guard RUNNING: si ya hay un scraping en curso (manual o de otro cron
        // job), no lo pisamos — registramos "skipped" y salimos sin tocar
        // precio/GPU (nada que restaurar, no llegamos a aplicarlos).
        if (scrape.estado() == ScraperStatus.RUNNING) {
            db.insertCronExecution(job.id(), now, "skipped", "scraper busy");
            db.touchLastRunAt(job.id(), now);
            db.pruneCronExecutions(job.id(), KEEP_EXECUTIONS);
            LOG.info("[CRON] Job {} ({}) saltado — ya hay un scraping en curso", job.id(), job.name());
            return;
        }

        long execId = db.insertCronExecution(job.id(), now, "running", null);
        db.touchLastRunAt(job.id(), now);

        RunLogCapture.Handle capture = logCapture.start(RUN_LOGGER);

        double prevMin = scrape.precioMinimo();
        double prevMax = scrape.precioMaximo();
        long startMillis = clock.millis();

        String status;
        String skippedReason = null;
        try {
            scrape.aplicarBandaDePrecio(job.precioMin(), job.precioMax());
            scrape.usarGpu(job.useGpu());

            Set<String> seleccion = (job.sitios() == null || job.sitios().isEmpty())
                    ? null : new HashSet<>(job.sitios());

            boolean started = scrape.iniciar(seleccion, job.forceRetrain());
            if (!started) {
                // TOCTOU: otro scraping arrancó entre el guard check y este punto.
                status = "skipped";
                skippedReason = "el scraper ganó la carrera antes de iniciar";
            } else {
                status = awaitTerminal();
            }
        } catch (Exception e) {
            status = "error";
            skippedReason = "excepción: " + e.getMessage();
            LOG.warn("[CRON] Job {} ({}) terminó con excepción: {}", job.id(), job.name(), e.getMessage());
        } finally {
            // Restaurar SIEMPRE, incluso si iniciarScraping/awaitTerminal explotó.
            scrape.aplicarBandaDePrecio(prevMin, prevMax);
            scrape.usarGpu(true);
            capture.close();
        }

        String finishedAt = LocalDateTime.now(clock).format(ISO_SECONDS);
        int durationMs = (int) (clock.millis() - startMillis);
        String logOutput = capture.lines();
        db.updateCronExecution(execId, finishedAt, status, skippedReason, logOutput, durationMs);
        db.pruneCronExecutions(job.id(), KEEP_EXECUTIONS);
    }

    /** Espera bloqueante (acotada) a que el scraping deje de estar RUNNING. */
    private String awaitTerminal() {
        long deadline = clock.millis() + MAX_WAIT_MS;
        while (scrape.estado() == ScraperStatus.RUNNING) {
            if (clock.millis() >= deadline) {
                LOG.warn("[CRON] Timeout esperando fin de scraping tras {} ms", MAX_WAIT_MS);
                return "error";
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return "error";
            }
        }
        return scrape.estado() == ScraperStatus.ERROR ? "error" : "success";
    }
}
