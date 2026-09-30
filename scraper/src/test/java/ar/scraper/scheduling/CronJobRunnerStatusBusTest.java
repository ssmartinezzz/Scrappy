package ar.scraper.scheduling;

import ar.scraper.scrape.ScrapeControlPort;
import ar.scraper.scrape.ScraperStatus;
import ar.scraper.scrape.StatusEvent;
import ar.scraper.scrape.StatusEvents;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Epic("Cron Scheduling")
@Feature("Job Execution")
@Story("Waiting for the scrape on the status bus")
@DisplayName("CronJobRunner — wakes on the bus, no polling")
class CronJobRunnerStatusBusTest {

    /** A bus that counts live subscriptions, so a leak is visible. */
    static final class CountingBus implements StatusEvents {
        final List<Consumer<StatusEvent>> listeners = new CopyOnWriteArrayList<>();
        final AtomicInteger subscribed = new AtomicInteger();

        @Override
        public void publish(StatusEvent event) {
            listeners.forEach(l -> l.accept(event));
        }

        @Override
        public Subscription subscribe(Consumer<StatusEvent> listener) {
            listeners.add(listener);
            subscribed.incrementAndGet();
            return () -> {
                listeners.remove(listener);
                subscribed.decrementAndGet();
            };
        }
    }

    private final Clock fixed = Clock.fixed(Instant.parse("2026-07-05T03:00:00Z"), ZoneId.of("UTC"));
    private final CountingBus bus = new CountingBus();
    private ScrapeControlPort scrape;
    private CronPort db;

    @BeforeEach
    void setUp() {
        scrape = mock(ScrapeControlPort.class);
        db = mock(CronPort.class);
        when(db.insertCronExecution(anyLong(), anyString(), eq("running"), any())).thenReturn(5L);
        when(scrape.iniciar(any(), anyBoolean())).thenReturn(true);
    }

    private CronJob job() {
        return new CronJob(1, "Nightly", 0, 0, List.of(), false, true, "0 0 3 * * *", true,
                "2026-07-01T00:00:00", "2026-07-01T00:00:00", null, "2026-07-05T03:00:00");
    }

    private void runAndPublishAfter(CronJobRunner runner, long publishAfterMs, StatusEvent event)
            throws Exception {
        Thread publisher = new Thread(() -> {
            try {
                Thread.sleep(publishAfterMs);
            } catch (InterruptedException e) {
                return;
            }
            bus.publish(event);
        });
        publisher.start();
        runner.runJob(job());
        publisher.join();
    }

    @Test
    @DisplayName("returns within 500 ms of the terminal event, while the state read still says RUNNING")
    void wakesOnTheTerminalEvent() throws Exception {
        when(scrape.estado()).thenReturn(ScraperStatus.IDLE, ScraperStatus.RUNNING);
        CronJobRunner runner = new CronJobRunner(scrape, db, fixed, RunLogCapture.NONE, bus);
        AtomicLong doneAt = new AtomicLong();

        Thread publisher = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return;
            }
            doneAt.set(System.nanoTime());
            bus.publish(new StatusEvent.ScrapeStatus(ScraperStatus.DONE, "Completado"));
        });
        publisher.start();
        runner.runJob(job());
        long returnedAt = System.nanoTime();
        publisher.join();

        assertThat((returnedAt - doneAt.get()) / 1_000_000).isLessThan(500);
        verify(db).updateCronExecution(eq(5L), anyString(), eq("success"), any(), any(), anyInt());
    }

    @Test
    @DisplayName("a terminal ERROR event records the execution as error")
    void errorEventRecordsError() throws Exception {
        when(scrape.estado()).thenReturn(ScraperStatus.IDLE, ScraperStatus.RUNNING);
        CronJobRunner runner = new CronJobRunner(scrape, db, fixed, RunLogCapture.NONE, bus);

        runAndPublishAfter(runner, 100, new StatusEvent.ScrapeStatus(ScraperStatus.ERROR, "boom"));

        verify(db).updateCronExecution(eq(5L), anyString(), eq("error"), any(), any(), anyInt());
    }

    @Test
    @DisplayName("progress and RUNNING messages do not end the wait")
    void nonTerminalEventsAreIgnored() throws Exception {
        when(scrape.estado()).thenReturn(ScraperStatus.IDLE, ScraperStatus.RUNNING);
        CronJobRunner runner = new CronJobRunner(scrape, db, fixed, RunLogCapture.NONE, bus);

        Thread noise = new Thread(() -> {
            try {
                Thread.sleep(100);
                bus.publish(new StatusEvent.ScrapeStatus(ScraperStatus.RUNNING, "1/3"));
                bus.publish(new StatusEvent.ScrapeProgress(3, 1, 10, List.of()));
                Thread.sleep(200);
                verify(db, never()).updateCronExecution(anyLong(), anyString(), anyString(), any(), any(), any());
                bus.publish(new StatusEvent.ScrapeStatus(ScraperStatus.DONE, "ok"));
            } catch (InterruptedException ignored) {
            }
        });
        noise.start();
        runner.runJob(job());
        noise.join();

        verify(db).updateCronExecution(eq(5L), anyString(), eq("success"), any(), any(), anyInt());
    }

    @Test
    @DisplayName("a run that finished between iniciar and the subscription is seen by the re-check")
    void finishedBeforeSubscribingIsNotMissed() {
        when(scrape.estado()).thenReturn(ScraperStatus.IDLE, ScraperStatus.DONE);
        CronJobRunner runner = new CronJobRunner(scrape, db, fixed, RunLogCapture.NONE, bus);

        runner.runJob(job());

        verify(db).updateCronExecution(eq(5L), anyString(), eq("success"), any(), any(), anyInt());
    }

    @Test
    @DisplayName("a lost event is recovered by the in-memory re-read, never by the database")
    void lostEventIsRecoveredByTheRecheck() {
        when(scrape.estado()).thenReturn(ScraperStatus.IDLE, ScraperStatus.RUNNING, ScraperStatus.RUNNING,
                ScraperStatus.ERROR);
        CronJobRunner runner = new CronJobRunner(scrape, db, fixed, RunLogCapture.NONE, bus, 30);

        runner.runJob(job());

        verify(db).updateCronExecution(eq(5L), anyString(), eq("error"), any(), any(), anyInt());
        verify(db, never()).listExecutions(anyLong(), anyInt());
        verify(db, never()).getExecution(anyLong());
    }

    @Test
    @DisplayName("the 2 h cap still applies, measured on the injected clock")
    void timesOutOnTheClock() {
        when(scrape.estado()).thenReturn(ScraperStatus.IDLE, ScraperStatus.RUNNING);
        AtomicLong now = new AtomicLong(0);
        Clock jumping = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(now.getAndAdd(3L * 60 * 60 * 1000));
            }

            @Override
            public long millis() {
                return now.getAndAdd(3L * 60 * 60 * 1000);
            }
        };
        CronJobRunner runner = new CronJobRunner(scrape, db, jumping, RunLogCapture.NONE, bus, 20);

        runner.runJob(job());

        verify(db).updateCronExecution(eq(5L), anyString(), eq("error"), any(), any(), anyInt());
    }

    @Test
    @DisplayName("the subscription is released after a normal end and after a timeout")
    void unsubscribesInFinally() {
        when(scrape.estado()).thenReturn(ScraperStatus.IDLE, ScraperStatus.DONE);
        new CronJobRunner(scrape, db, fixed, RunLogCapture.NONE, bus).runJob(job());
        assertThat(bus.subscribed.get()).isZero();

        when(scrape.estado()).thenReturn(ScraperStatus.IDLE, ScraperStatus.RUNNING, ScraperStatus.ERROR);
        new CronJobRunner(scrape, db, fixed, RunLogCapture.NONE, bus, 20).runJob(job());
        assertThat(bus.subscribed.get()).isZero();
    }
}
