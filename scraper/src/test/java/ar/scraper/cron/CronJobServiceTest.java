package ar.scraper.cron;

import ar.scraper.db.DatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CronJobService} — nextRunAt computation (pure,
 * {@link org.springframework.scheduling.support.CronExpression}), the
 * create/update CRUD facade, and the {@code tick()} poller dispatch logic.
 * No Spring context — plain Mockito, matching the rest of the codebase's test
 * style (see {@code ApiControllerFinanciacionTest}).
 */
class CronJobServiceTest {

    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    // 2026-07-05T10:00:00 (fixed "now" for every test)
    private final Clock clock = Clock.fixed(
            ZonedDateTime.of(2026, 7, 5, 10, 0, 0, 0, ZONE).toInstant(), ZONE);

    private DatabaseService db;
    private CronJobRunner runner;
    private CronJobService service;

    @BeforeEach
    void setUp() {
        db = mock(DatabaseService.class);
        runner = mock(CronJobRunner.class);
        service = new CronJobService(db, runner, clock);
    }

    private CronJob job(long id, boolean enabled, String nextRunAt) {
        return new CronJob(id, "Job " + id, 1000, 50000, List.of("Freres"),
                true, false, "0 0 3 * * *", enabled,
                "2026-07-01T00:00:00", "2026-07-01T00:00:00", null, nextRunAt);
    }

    // ── computeNextRun (pure) ────────────────────────────────────────────────

    @Test
    void computeNextRunReturnsNextFireTimeForValidCronExpr() {
        String next = service.computeNextRun("0 0 3 * * *", ZonedDateTime.now(clock));

        // "now" is 2026-07-05T10:00 (past 03:00) -> next fire is tomorrow 03:00
        assertThat(next).isEqualTo("2026-07-06T03:00:00");
    }

    @Test
    void computeNextRunThrowsForInvalidCronExpr() {
        assertThatThrownBy(() -> service.computeNextRun("not a cron expr", ZonedDateTime.now(clock)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── isValidCronExpr (pure, non-throwing — used by CronApiController) ─────

    @Test
    void isValidCronExprReturnsTrueForValidExpr() {
        assertThat(service.isValidCronExpr("0 0 3 * * *")).isTrue();
    }

    @Test
    void isValidCronExprReturnsFalseForInvalidExpr() {
        assertThat(service.isValidCronExpr("not a cron expr")).isFalse();
    }

    // ── createJob / updateJob ────────────────────────────────────────────────

    @Test
    void createJobComputesNextRunAtAndDelegatesToDb() {
        when(db.insertCronJob(any(), anyDouble(), anyDouble(), any(), anyBoolean(), anyBoolean(),
                any(), anyBoolean(), any())).thenReturn(42L);

        long id = service.createJob("Nightly", 1000, 50000, List.of("Freres"),
                true, false, "0 0 3 * * *", true);

        assertThat(id).isEqualTo(42L);
        verify(db).insertCronJob(eq("Nightly"), eq(1000.0), eq(50000.0), eq(List.of("Freres")),
                eq(true), eq(false), eq("0 0 3 * * *"), eq(true), eq("2026-07-06T03:00:00"));
    }

    @Test
    void createJobWithDisabledLeavesNextRunAtNull() {
        when(db.insertCronJob(any(), anyDouble(), anyDouble(), any(), anyBoolean(), anyBoolean(),
                any(), anyBoolean(), any())).thenReturn(1L);

        service.createJob("Disabled", 0, 100000, List.of(), false, true, "0 0 3 * * *", false);

        verify(db).insertCronJob(eq("Disabled"), anyDouble(), anyDouble(), any(),
                anyBoolean(), anyBoolean(), any(), eq(false), isNull());
    }

    @Test
    void updateJobRecomputesNextRunAtAndDelegatesToDb() {
        when(db.updateCronJob(anyLong(), any(), anyDouble(), anyDouble(), any(), anyBoolean(),
                anyBoolean(), any(), anyBoolean(), any())).thenReturn(true);

        boolean ok = service.updateJob(7, "Editado", 2000, 60000, List.of("Sporting"),
                false, true, "0 0 5 * * *", true);

        assertThat(ok).isTrue();
        // "now" is 2026-07-05T10:00 (past 05:00) -> next fire is tomorrow 05:00
        verify(db).updateCronJob(eq(7L), eq("Editado"), eq(2000.0), eq(60000.0), eq(List.of("Sporting")),
                eq(false), eq(true), eq("0 0 5 * * *"), eq(true), eq("2026-07-06T05:00:00"));
    }

    // ── dueJobs (pure) ───────────────────────────────────────────────────────

    @Test
    void dueJobsIncludesEnabledJobsWithPastOrNullNextRunAt() {
        CronJob due = job(1, true, "2026-07-05T09:00:00");       // past -> due
        CronJob exactlyNow = job(2, true, "2026-07-05T10:00:00"); // == now -> due
        CronJob future = job(3, true, "2026-07-05T11:00:00");     // future -> not due
        CronJob disabled = job(4, false, "2026-07-05T09:00:00");  // disabled -> not due
        CronJob neverScheduled = job(5, true, null);              // null -> due

        List<CronJob> due2 = service.dueJobs(
                List.of(due, exactlyNow, future, disabled, neverScheduled), ZonedDateTime.now(clock));

        assertThat(due2).extracting(CronJob::id).containsExactlyInAnyOrder(1L, 2L, 5L);
    }

    // ── tick() ───────────────────────────────────────────────────────────────

    @Test
    void tickRunsDueJobAndReschedulesNextRun() {
        CronJob due = job(10, true, "2026-07-05T09:00:00");
        when(db.listCronJobs()).thenReturn(List.of(due));

        service.tick();

        verify(runner).runJob(due);
        verify(db).updateNextRunAt(10L, "2026-07-06T03:00:00");
    }

    @Test
    void tickSkipsJobsNotYetDue() {
        CronJob future = job(11, true, "2026-07-05T11:00:00");
        when(db.listCronJobs()).thenReturn(List.of(future));

        service.tick();

        verify(runner, never()).runJob(any());
        verify(db, never()).updateNextRunAt(anyLong(), any());
    }

    @Test
    void tickSkipsDisabledJobs() {
        CronJob disabled = job(12, false, "2026-07-05T09:00:00");
        when(db.listCronJobs()).thenReturn(List.of(disabled));

        service.tick();

        verify(runner, never()).runJob(any());
    }

    @Test
    void tickContinuesAfterOneJobThrows() {
        CronJob failing = job(20, true, "2026-07-05T09:00:00");
        CronJob ok = job(21, true, "2026-07-05T09:00:00");
        when(db.listCronJobs()).thenReturn(List.of(failing, ok));
        doThrow(new RuntimeException("boom")).when(runner).runJob(failing);

        service.tick();

        verify(runner).runJob(failing);
        verify(runner).runJob(ok);
        // both get rescheduled even though the first one blew up
        verify(db).updateNextRunAt(eq(20L), any());
        verify(db).updateNextRunAt(eq(21L), any());
    }

    // ── triggerNow (manual run-now, async dispatch) ─────────────────────────

    @Test
    void triggerNowReturnsNotFoundWhenJobAbsent() {
        when(db.getCronJob(999L)).thenReturn(Optional.empty());

        CronJobService.RunNowResult result = service.triggerNow(999);

        assertThat(result).isEqualTo(CronJobService.RunNowResult.NOT_FOUND);
        verify(runner, never()).runJob(any());
    }

    @Test
    void triggerNowReturnsBusyWhenScraperIsRunning() {
        CronJob due = job(1, true, "2026-07-05T09:00:00");
        when(db.getCronJob(1L)).thenReturn(Optional.of(due));
        when(runner.isScraperBusy()).thenReturn(true);

        CronJobService.RunNowResult result = service.triggerNow(1);

        assertThat(result).isEqualTo(CronJobService.RunNowResult.BUSY);
        verify(runner, never()).runJob(any());
    }

    @Test
    void triggerNowReturnsBusyWhenJobAlreadyInFlight() throws InterruptedException {
        CronJob due = job(31, true, "2026-07-05T09:00:00");
        when(db.getCronJob(31L)).thenReturn(Optional.of(due));
        when(runner.isScraperBusy()).thenReturn(false);

        CountDownLatch runJobStarted = new CountDownLatch(1);
        CountDownLatch releaseRunJob = new CountDownLatch(1);
        doAnswer(inv -> {
            runJobStarted.countDown();
            releaseRunJob.await(2, TimeUnit.SECONDS);
            return null;
        }).when(runner).runJob(due);

        CronJobService.RunNowResult first = service.triggerNow(31);
        assertThat(first).isEqualTo(CronJobService.RunNowResult.STARTED);
        // Wait until the dispatched virtual thread is actually inside runJob()
        // before firing the second call, so the inFlight guard is deterministic.
        assertThat(runJobStarted.await(2, TimeUnit.SECONDS)).isTrue();

        CronJobService.RunNowResult second = service.triggerNow(31);
        assertThat(second).isEqualTo(CronJobService.RunNowResult.BUSY);

        releaseRunJob.countDown(); // let the first dispatch finish, avoid a hung thread
    }

    @Test
    void triggerNowStartedDispatchesRunnerAsynchronouslyAndReschedules() throws InterruptedException {
        CronJob due = job(30, true, "2026-07-05T09:00:00");
        when(db.getCronJob(30L)).thenReturn(Optional.of(due));
        when(runner.isScraperBusy()).thenReturn(false);

        CountDownLatch runJobCalled = new CountDownLatch(1);
        CountDownLatch rescheduled = new CountDownLatch(1);
        doAnswer(inv -> { runJobCalled.countDown(); return null; }).when(runner).runJob(due);
        doAnswer(inv -> { rescheduled.countDown(); return null; })
                .when(db).updateNextRunAt(eq(30L), any());

        CronJobService.RunNowResult result = service.triggerNow(30);

        assertThat(result).isEqualTo(CronJobService.RunNowResult.STARTED);
        assertThat(runJobCalled.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(rescheduled.await(2, TimeUnit.SECONDS)).isTrue();
        verify(runner).runJob(due);
        verify(db).updateNextRunAt(eq(30L), eq("2026-07-06T03:00:00"));
    }
}
