package ar.scraper.scheduling;

import ar.scraper.scrape.ScrapeControlPort;
import ar.scraper.scrape.ScraperStatus;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@Epic("Cron Scheduling")
@Feature("Job Execution")
@Story("Runner")
@DisplayName("CronJobRunner — RUNNING guard, price/GPU restore, execution recording")
class CronJobRunnerTest {

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-05T03:00:00Z"), ZoneId.of("UTC"));

    private ScrapeControlPort scrape;
    private CronPort db;
    private CronJobRunner runner;

    @BeforeEach
    void setUp() {
        wireRunner();
    }

    @Step("Wire CronJobRunner with mocked collaborators")
    private void wireRunner() {
        scrape = mock(ScrapeControlPort.class);
        db = mock(CronPort.class);
        runner = new CronJobRunner(scrape, db, clock);
    }

    private CronJob job() {
        return new CronJob(1, "Nightly", 1000, 50000, List.of("Freres", "VCP"),
                true, false, "0 0 3 * * *", true,
                "2026-07-01T00:00:00", "2026-07-01T00:00:00", null, "2026-07-05T03:00:00");
    }

    @Test
    void runJobSkipsAndRecordsExecutionWhenScraperAlreadyRunning() {
        when(scrape.estado()).thenReturn(ScraperStatus.RUNNING);

        runner.runJob(job());

        verify(scrape, never()).iniciar(any(), anyBoolean());
        verify(db).insertCronExecution(eq(1L), anyString(), eq("skipped"), anyString());
        verify(db, never()).updateCronExecution(anyLong(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void runJobRestoresPriceAndGpuFlagInFinallyEvenWhenIniciarScrapingThrows() {
        when(scrape.estado()).thenReturn(ScraperStatus.IDLE);
        when(scrape.precioMinimo()).thenReturn(100.0);
        when(scrape.precioMaximo()).thenReturn(200.0);
        when(db.insertCronExecution(anyLong(), anyString(), eq("running"), any())).thenReturn(99L);
        doThrow(new RuntimeException("boom"))
                .when(scrape).iniciar(any(), anyBoolean());

        runner.runJob(job());

        // job's own range applied before the throw, then restored to prev values in finally
        verify(scrape).aplicarBandaDePrecio(1000.0, 50000.0);
        verify(scrape).aplicarBandaDePrecio(100.0, 200.0);

        verify(scrape).usarGpu(false); // job.useGpu()==false, applied before the throw
        verify(scrape).usarGpu(true);  // reset to default in finally

        verify(db).updateCronExecution(eq(99L), anyString(), eq("error"), any(), any(), anyInt());
        verify(db).touchLastRunAt(eq(1L), anyString());
        verify(db).pruneCronExecutions(eq(1L), eq(50));
    }

    @Test
    void runJobRecordsSuccessAndPassesSelectionAndForceRetrain() {
        when(scrape.estado())
                .thenReturn(ScraperStatus.IDLE)   // guard check
                .thenReturn(ScraperStatus.DONE);  // awaitTerminal + final check
        when(db.insertCronExecution(anyLong(), anyString(), eq("running"), any())).thenReturn(5L);
        when(scrape.iniciar(any(), anyBoolean())).thenReturn(true);

        runner.runJob(job());

        verify(scrape).iniciar(eq(Set.of("Freres", "VCP")), eq(true));
        verify(db).updateCronExecution(eq(5L), anyString(), eq("success"), any(), any(), anyInt());
        verify(db).pruneCronExecutions(eq(1L), eq(50));
    }

    // ── isScraperBusy (used by CronJobService.triggerNow for run-now) ────────

    @Test
    void isScraperBusyReturnsTrueWhenRunning() {
        when(scrape.estado()).thenReturn(ScraperStatus.RUNNING);

        assertThat(runner.isScraperBusy()).isTrue();
    }

    @Test
    void isScraperBusyReturnsFalseWhenNotRunning() {
        when(scrape.estado()).thenReturn(ScraperStatus.IDLE);

        assertThat(runner.isScraperBusy()).isFalse();
    }
}
