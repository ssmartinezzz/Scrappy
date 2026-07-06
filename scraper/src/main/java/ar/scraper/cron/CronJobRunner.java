package ar.scraper.cron;

import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.web.ScraperService;
import ar.scraper.web.ScraperService.ScraperStatus;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
 * {@link ScraperService#iniciarScraping} SIN CAMBIOS, espera bloqueante
 * acotada hasta que el scraping termine, captura del logger
 * {@code ar.scraper.run} para esa ventana, y registro/retención de la
 * ejecución en {@code cron_executions}.
 */
@Component
public class CronJobRunner {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(CronJobRunner.class);
    private static final int KEEP_EXECUTIONS = 50;
    private static final long POLL_INTERVAL_MS = 5_000L;
    private static final long MAX_WAIT_MS = 2L * 60 * 60 * 1000; // 2h, cota generosa
    private static final DateTimeFormatter ISO_SECONDS = CronJobService.ISO_SECONDS;

    private final ScraperService scraperService;
    private final ScraperConfig config;
    private final PythonRunner pythonRunner;
    private final DatabaseService db;
    private final Clock clock;

    public CronJobRunner(ScraperService scraperService, ScraperConfig config,
            PythonRunner pythonRunner, DatabaseService db, Clock clock) {
        this.scraperService = scraperService;
        this.config = config;
        this.pythonRunner = pythonRunner;
        this.db = db;
        this.clock = clock;
    }

    public void runJob(CronJob job) {
        String now = LocalDateTime.now(clock).format(ISO_SECONDS);

        // Guard RUNNING: si ya hay un scraping en curso (manual o de otro cron
        // job), no lo pisamos — registramos "skipped" y salimos sin tocar
        // precio/GPU (nada que restaurar, no llegamos a aplicarlos).
        if (scraperService.getStatus() == ScraperStatus.RUNNING) {
            db.insertCronExecution(job.id(), now, "skipped", "scraper busy");
            db.touchLastRunAt(job.id(), now);
            db.pruneCronExecutions(job.id(), KEEP_EXECUTIONS);
            LOG.info("[CRON] Job {} ({}) saltado — ya hay un scraping en curso", job.id(), job.name());
            return;
        }

        long execId = db.insertCronExecution(job.id(), now, "running", null);
        db.touchLastRunAt(job.id(), now);

        RunLogCapture capture = attachRunLogAppender();

        double prevMin = config.getPrecioMinimo();
        double prevMax = config.getPrecioMaximo();
        long startMillis = clock.millis();

        String status;
        String skippedReason = null;
        try {
            config.setPrecioMinimo(job.precioMin());
            config.setPrecioMaximo(job.precioMax());
            pythonRunner.setUseGpu(job.useGpu());

            Set<String> seleccion = (job.sitios() == null || job.sitios().isEmpty())
                    ? null : new HashSet<>(job.sitios());

            boolean started = scraperService.iniciarScraping(seleccion, job.forceRetrain());
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
            config.setPrecioMinimo(prevMin);
            config.setPrecioMaximo(prevMax);
            pythonRunner.setUseGpu(true);
            detachRunLogAppender(capture);
        }

        String finishedAt = LocalDateTime.now(clock).format(ISO_SECONDS);
        int durationMs = (int) (clock.millis() - startMillis);
        String logOutput = drain(capture);
        db.updateCronExecution(execId, finishedAt, status, skippedReason, logOutput, durationMs);
        db.pruneCronExecutions(job.id(), KEEP_EXECUTIONS);
    }

    /** Espera bloqueante (acotada) a que el scraping deje de estar RUNNING. */
    private String awaitTerminal() {
        long deadline = clock.millis() + MAX_WAIT_MS;
        while (scraperService.getStatus() == ScraperStatus.RUNNING) {
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
        return scraperService.getStatus() == ScraperStatus.ERROR ? "error" : "success";
    }

    /** Par logger/appender de una ventana de captura (ver ADR-4 del design). */
    private record RunLogCapture(Logger logger, ListAppender<ILoggingEvent> appender) {}

    /** Adjunta un {@link ListAppender} al logger {@code ar.scraper.run} para esta corrida. */
    private RunLogCapture attachRunLogAppender() {
        org.slf4j.Logger raw = LoggerFactory.getLogger("ar.scraper.run");
        if (!(raw instanceof Logger runLogger)) return new RunLogCapture(null, null); // no es logback-classic
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        runLogger.addAppender(appender);
        return new RunLogCapture(runLogger, appender);
    }

    private void detachRunLogAppender(RunLogCapture capture) {
        if (capture.logger() == null || capture.appender() == null) return;
        capture.logger().detachAppender(capture.appender());
        capture.appender().stop();
    }

    private String drain(RunLogCapture capture) {
        if (capture.appender() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent evt : capture.appender().list) {
            sb.append(evt.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }
}
