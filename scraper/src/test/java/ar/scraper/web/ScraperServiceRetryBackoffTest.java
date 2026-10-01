package ar.scraper.web;

import ar.scraper.model.ScrapeResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** The backoff between attempts is linear (base x attempt) and an interrupt is not swallowed. */
class ScraperServiceRetryBackoffTest {

    @Test
    void waitsBaseTimesAttemptBetweenAttemptsAndNotAfterTheLast() throws Exception {
        List<Long> at = new ArrayList<>();
        long t0 = System.nanoTime();

        ScrapeResult r = ScraperService.withRetry(() -> {
            at.add((System.nanoTime() - t0) / 1_000_000);
            throw new IllegalStateException("down");
        }, 3, 100);

        assertThat(r.error()).isEqualTo("down");
        assertThat(at).hasSize(3);
        assertThat(at.get(1) - at.get(0)).as("first wait = 1 x base").isBetween(95L, 700L);
        assertThat(at.get(2) - at.get(1)).as("second wait = 2 x base").isBetween(195L, 900L);
        assertThat((System.nanoTime() - t0) / 1_000_000 - at.get(2)).as("no wait after the last attempt")
                .isLessThan(300L);
    }

    @Test
    void anInterruptDuringTheBackoffEndsTheRetriesAndIsNotSwallowed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Throwable[] thrown = new Throwable[1];
        boolean[] stillInterrupted = new boolean[1];
        Thread worker = new Thread(() -> {
            try {
                ScraperService.withRetry(() -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("down");
                }, 5, 5_000);
            } catch (Throwable t) {
                thrown[0] = t;
                stillInterrupted[0] = Thread.currentThread().isInterrupted();
            }
        });
        worker.start();
        Thread.sleep(300);
        worker.interrupt();
        worker.join(3_000);

        assertThat(worker.isAlive()).isFalse();
        assertThat(thrown[0]).isInstanceOf(InterruptedException.class);
        assertThat(stillInterrupted[0]).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void maxAttemptsOfOneMeansASingleCallAndNoWait() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        ScrapeResult r = ScraperService.withRetry(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("once");
        }, 1, 1_000);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(r.error()).isEqualTo("once");
    }
}
