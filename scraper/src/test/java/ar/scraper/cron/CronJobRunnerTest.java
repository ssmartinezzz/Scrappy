package ar.scraper.cron;

import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.ml.PythonRunner;
import ar.scraper.web.ScraperService;
import ar.scraper.web.ScraperService.ScraperStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CronJobRunner} — replicates the {@code /api/scrape}
 * recipe for a single {@link CronJob}: RUNNING guard (skip), price/GPU-flag
 * restore in {@code finally} even on failure, and the happy path. Mockito
 * only, no Spring context (matches {@code ApiControllerFinanciacionTest}
 * style).
 */
class CronJobRunnerTest {

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-05T03:00:00Z"), ZoneId.of("UTC"));

    private ScraperService scraperService;
    private ScraperConfig config;
    private PythonRunner pythonRunner;
    private DatabaseService db;
    private CronJobRunner runner;

    @BeforeEach
    void setUp() {
        scraperService = mock(ScraperService.class);
        config = mock(ScraperConfig.class);
        pythonRunner = mock(PythonRunner.class);
        db = mock(DatabaseService.class);
        runner = new CronJobRunner(scraperService, config, pythonRunner, db, clock);
    }

    private CronJob job() {
        return new CronJob(1, "Nightly", 1000, 50000, List.of("Freres", "VCP"),
                true, false, "0 0 3 * * *", true,
                "2026-07-01T00:00:00", "2026-07-01T00:00:00", null, "2026-07-05T03:00:00");
    }

    @Test
    void runJobSkipsAndRecordsExecutionWhenScraperAlreadyRunning() {
        when(scraperService.getStatus()).thenReturn(ScraperStatus.RUNNING);

        runner.runJob(job());

        verify(scraperService, never()).iniciarScraping(any(), anyBoolean());
        verify(db).insertCronExecution(eq(1L), anyString(), eq("skipped"), anyString());
        verify(db, never()).updateCronExecution(anyLong(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void runJobRestoresPriceAndGpuFlagInFinallyEvenWhenIniciarScrapingThrows() {
        when(scraperService.getStatus()).thenReturn(ScraperStatus.IDLE);
        when(config.getPrecioMinimo()).thenReturn(100.0);
        when(config.getPrecioMaximo()).thenReturn(200.0);
        when(db.insertCronExecution(anyLong(), anyString(), eq("running"), any())).thenReturn(99L);
        doThrow(new RuntimeException("boom"))
                .when(scraperService).iniciarScraping(any(), anyBoolean());

        runner.runJob(job());

        // job's own range applied before the throw, then restored to prev values in finally
        verify(config).setPrecioMinimo(1000.0);
        verify(config).setPrecioMaximo(50000.0);
        verify(config).setPrecioMinimo(100.0);
        verify(config).setPrecioMaximo(200.0);

        verify(pythonRunner).setUseGpu(false); // job.useGpu()==false, applied before the throw
        verify(pythonRunner).setUseGpu(true);  // reset to default in finally

        verify(db).updateCronExecution(eq(99L), anyString(), eq("error"), any(), any(), anyInt());
        verify(db).touchLastRunAt(eq(1L), anyString());
        verify(db).pruneCronExecutions(eq(1L), eq(50));
    }

    @Test
    void runJobRecordsSuccessAndPassesSelectionAndForceRetrain() {
        when(scraperService.getStatus())
                .thenReturn(ScraperStatus.IDLE)   // guard check
                .thenReturn(ScraperStatus.DONE);  // awaitTerminal + final check
        when(db.insertCronExecution(anyLong(), anyString(), eq("running"), any())).thenReturn(5L);
        when(scraperService.iniciarScraping(any(), anyBoolean())).thenReturn(true);

        runner.runJob(job());

        verify(scraperService).iniciarScraping(eq(Set.of("Freres", "VCP")), eq(true));
        verify(db).updateCronExecution(eq(5L), anyString(), eq("success"), any(), any(), anyInt());
        verify(db).pruneCronExecutions(eq(1L), eq(50));
    }

    // ── isScraperBusy (used by CronJobService.triggerNow for run-now) ────────

    @Test
    void isScraperBusyReturnsTrueWhenRunning() {
        when(scraperService.getStatus()).thenReturn(ScraperStatus.RUNNING);

        assertThat(runner.isScraperBusy()).isTrue();
    }

    @Test
    void isScraperBusyReturnsFalseWhenNotRunning() {
        when(scraperService.getStatus()).thenReturn(ScraperStatus.IDLE);

        assertThat(runner.isScraperBusy()).isFalse();
    }
}
