package ar.scraper.web;

import ar.scraper.model.ScrapeResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScraperService#withRetry(Callable, int, long)}.
 *
 * Uses {@code baseDelayMs=0} so no real sleeping occurs during test execution.
 */
class ScraperServiceRetryTest {

    // Helper to build a minimal success result
    private static ScrapeResult success() {
        return new ScrapeResult("test", List.of(), null, 0);
    }

    @Test
    void retrySucceedsOnThirdAttempt() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger(0);

        Callable<ScrapeResult> callable = () -> {
            int n = calls.incrementAndGet();
            if (n < 3) throw new RuntimeException("transient error attempt " + n);
            return success();
        };

        ScrapeResult result = ScraperService.withRetry(callable, 3, 0);

        assertThat(result.error()).isNull();
        assertThat(result.productos()).isEmpty(); // success() returns empty list, but no error
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void retryExhaustedReturnsErrorResult() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger(0);

        Callable<ScrapeResult> callable = () -> {
            calls.incrementAndGet();
            throw new RuntimeException("network error");
        };

        ScrapeResult result = ScraperService.withRetry(callable, 3, 0);

        assertThat(result.productos()).isEmpty();
        assertThat(result.error()).contains("network error");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void retrySucceedsImmediately() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger(0);

        Callable<ScrapeResult> callable = () -> {
            calls.incrementAndGet();
            return success();
        };

        ScrapeResult result = ScraperService.withRetry(callable, 3, 0);

        assertThat(result.error()).isNull();
        assertThat(calls.get()).isEqualTo(1);
    }
}
