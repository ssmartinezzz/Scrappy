package ar.scraper.web;

import ar.scraper.model.ScrapeResult;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scrape-run-persistence-and-resume, slice 3 — cancelling must not open browsers.
 *
 * <p>Each site task runs inside {@code withRetry(..., 3, 2000)}. Every attempt
 * builds a fresh {@code Playwright}, which is a real browser process. The
 * cancellation design has surviving browsers closed from the outside, and that
 * close makes the blocking call in flight throw — which the retry loop reads as
 * "attempt failed, try again". So a cancellation would spawn up to two more
 * browsers per site: <b>cancelling would open browsers instead of closing
 * them</b>, and the more sites were running, the worse it gets.</p>
 *
 * <p>The flag therefore has to be consulted inside the retry decision, not only
 * in the loop that waits for results. Found by measurement, not by reading the
 * task list — no task covers it.</p>
 */
@Epic("Scraping")
@Feature("Cancellation")
@Story("withRetry stops retrying once the run is cancelled")
@DisplayName("ScraperService.withRetry — under cancellation")
class ScraperServiceCancelRetryTest {

    private static ScrapeResult falla() throws Exception {
        throw new IllegalStateException("el browser se cerró desde afuera");
    }

    @Test
    @DisplayName("a cancellation between attempts stops the retries dead")
    void cancellingStopsTheRetries() throws Exception {
        AtomicInteger intentos = new AtomicInteger(0);
        AtomicBoolean cancelado = new AtomicBoolean(false);

        ScrapeResult r = ScraperService.withRetry(() -> {
            intentos.incrementAndGet();
            cancelado.set(true);   // lo que hace cerrar el browser desde afuera
            return falla();
        }, 3, 0, cancelado::get);

        assertThat(intentos)
                .as("each further attempt would create another Playwright, so a "
                    + "cancellation that retries is a cancellation that spawns browsers")
                .hasValue(1);
        assertThat(r.productos()).isEmpty();
    }

    @Test
    @DisplayName("an already-cancelled run never starts the task at all")
    void anAlreadyCancelledRunNeverRuns() throws Exception {
        AtomicInteger intentos = new AtomicInteger(0);

        ScrapeResult r = ScraperService.withRetry(() -> {
            intentos.incrementAndGet();
            return new ScrapeResult("x", List.of(), null, 0);
        }, 3, 0, () -> true);

        assertThat(intentos)
                .as("a site whose turn comes after the cancel must not launch a browser")
                .hasValue(0);
        assertThat(r.productos()).isEmpty();
    }

    @Test
    @DisplayName("without a cancellation the retries behave exactly as before")
    void withoutCancellationNothingChanges() throws Exception {
        AtomicInteger intentos = new AtomicInteger(0);

        ScrapeResult r = ScraperService.withRetry(() -> {
            intentos.incrementAndGet();
            return falla();
        }, 3, 0, () -> false);

        // The contrast that makes the first test mean something: if the retries
        // were simply gone, "it stopped retrying" would be free.
        assertThat(intentos).as("three attempts, like the existing 3-arg overload").hasValue(3);
        assertThat(r.error()).isNotBlank();
    }
}
