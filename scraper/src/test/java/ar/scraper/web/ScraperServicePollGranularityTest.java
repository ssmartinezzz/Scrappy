package ar.scraper.web;

import ar.scraper.model.ScrapeResult;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scrape-run-persistence-and-resume, slice 3 (tasks 3.1-3.3) — the wait, split
 * from the budget.
 *
 * <p>The loop used to block for up to {@code min(TIMEOUT_POR_SITIO_S=600,
 * remaining)} inside a single {@code ecs.poll}, so a cancellation flag checked
 * only between iterations could sit unnoticed for <b>ten minutes</b>. Cancel
 * that takes ten minutes reads as cancel that is broken.</p>
 *
 * <p><b>And the obvious fix is wrong</b>, which is why {@link
 * #shortPollsDoNotConsumeSiteSlots()} exists. Simply shortening {@code wait}
 * makes the timed-out poll fall through to {@code continue}, and that
 * {@code continue} advances the outer {@code for (i < totalSitios)} — so each
 * empty poll spends one site's slot. At five seconds a run would exhaust all 26
 * slots in about 130 seconds and finish having collected almost nothing, with
 * every site still working. The budget must stay per-site; only the granularity
 * of waiting changes.</p>
 *
 * <p>Granularity is a parameter rather than the production constant so these
 * run in milliseconds and assert an ordering of events, not a wall clock.</p>
 */
@Epic("Scraping")
@Feature("Cancellation")
@Story("Poll granularity — responsive without spending site slots")
@DisplayName("ScraperService — waiting for a site result")
class ScraperServicePollGranularityTest {

    private static final long GRANULARIDAD_MS = 20;

    private ExecutorService exec;

    @AfterEach
    void tearDown() {
        if (exec != null) exec.shutdownNow();
    }

    private ExecutorCompletionService<ScrapeResult> completionService() {
        exec = Executors.newFixedThreadPool(2);
        return new ExecutorCompletionService<>(exec);
    }

    private static ScrapeResult resultado(String sitio) {
        return new ScrapeResult(sitio, List.of(), null, 0);
    }

    @Test
    @DisplayName("returns the result as soon as a site finishes")
    void returnsTheResult() throws Exception {
        var ecs = completionService();
        ecs.submit(() -> resultado("freres"));

        Future<ScrapeResult> f = ScraperService.esperarResultado(
                ecs, System.currentTimeMillis() + 5_000, GRANULARIDAD_MS, new AtomicBoolean(false));

        assertThat(f).isNotNull();
        assertThat(f.get().sitio()).isEqualTo("freres");
    }

    @Test
    @DisplayName("a cancellation mid-wait is noticed within one poll, not at the deadline")
    void cancellationIsNoticedWithinOnePoll() throws Exception {
        var ecs = completionService();
        AtomicBoolean cancelado = new AtomicBoolean(false);
        // Nothing will ever complete; only the flag can end this wait.
        long deadlineLejano = System.currentTimeMillis() + 30_000;

        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(GRANULARIDAD_MS * 2); } catch (InterruptedException ignored) { }
            cancelado.set(true);
        });

        long t0 = System.currentTimeMillis();
        Future<ScrapeResult> f = ScraperService.esperarResultado(
                ecs, deadlineLejano, GRANULARIDAD_MS, cancelado);
        long transcurrido = System.currentTimeMillis() - t0;

        assertThat(f).as("nothing completed, so there is no result to return").isNull();
        assertThat(transcurrido)
                .as("the whole point: the flag is checked every poll, so cancel does "
                    + "not wait out a per-site budget that can be ten minutes long")
                .isLessThan(5_000);
    }

    @Test
    @DisplayName("empty polls do NOT give up early — they keep waiting out the budget")
    void shortPollsDoNotConsumeSiteSlots() throws Exception {
        var ecs = completionService();
        // Completes only after several granularity windows have gone by empty.
        ecs.submit(() -> {
            Thread.sleep(GRANULARIDAD_MS * 6);
            return resultado("entreno");
        });

        Future<ScrapeResult> f = ScraperService.esperarResultado(
                ecs, System.currentTimeMillis() + 5_000, GRANULARIDAD_MS, new AtomicBoolean(false));

        // The rejected shortcut returns null here, and the caller's `continue`
        // would spend a site slot on a site that was still working. Six empty
        // polls must cost nothing.
        assertThat(f)
                .as("shortening the wait instead of splitting it burns all 26 slots "
                    + "in ~130s and collects nothing")
                .isNotNull();
        assertThat(f.get().sitio()).isEqualTo("entreno");
    }

    @Test
    @DisplayName("returns null when the per-site budget genuinely runs out")
    void returnsNullAtTheDeadline() throws Exception {
        var ecs = completionService();

        long t0 = System.currentTimeMillis();
        Future<ScrapeResult> f = ScraperService.esperarResultado(
                ecs, System.currentTimeMillis() + GRANULARIDAD_MS * 3, GRANULARIDAD_MS,
                new AtomicBoolean(false));
        long transcurrido = System.currentTimeMillis() - t0;

        assertThat(f).as("the budget is still per-site; only the waiting is split").isNull();
        assertThat(transcurrido)
                .as("it waits out the deadline rather than returning on the first empty poll")
                .isGreaterThanOrEqualTo(GRANULARIDAD_MS * 2);
    }

    @Test
    @DisplayName("a flag already set returns immediately, without polling once")
    void anAlreadyCancelledRunDoesNotWaitAtAll() throws Exception {
        var ecs = completionService();
        AtomicInteger jamasCorre = new AtomicInteger(0);
        ecs.submit(() -> { jamasCorre.incrementAndGet(); return resultado("x"); });

        long t0 = System.currentTimeMillis();
        Future<ScrapeResult> f = ScraperService.esperarResultado(
                ecs, System.currentTimeMillis() + 30_000, GRANULARIDAD_MS, new AtomicBoolean(true));

        assertThat(f).isNull();
        assertThat(System.currentTimeMillis() - t0)
                .as("checked before the first poll, so a cancel that arrives between "
                    + "sites is not made to wait for a poll window")
                .isLessThan(GRANULARIDAD_MS * 5);
    }
}
