package ar.scraper.web;

import ar.scraper.model.ScrapeResult;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Harvey symptom: one site outliving its per-site timeout while the global
 * deadline is still far away must be collected, not abandoned RUNNING.
 */
@Epic("Scraping")
@Feature("Site result collection")
@Story("A per-site timeout retries the same slot instead of abandoning it")
@DisplayName("SiteResultCollector — collecting results without burning slots")
class SiteResultCollectorTest {

    private static final long GRANULARIDAD_MS = 20;
    // Whole seconds only (the production budget is per-site-in-seconds), so 1
    // is the smallest usable per-site window.
    private static final long TIMEOUT_POR_SITIO_S = 1;
    // Production grace is 2 real seconds; a small value here keeps the tests
    // that need to cross it fast.
    private static final long GRACE_MS = 50;

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
    @DisplayName("a single slow site is still collected after its per-site timeout expires")
    void unSitioLentoNoSeAbandona() throws Exception {
        var ecs = completionService();
        // Needs to survive one full TIMEOUT_POR_SITIO_S window (1000ms) PLUS
        // the grace (50ms) before answering, but finishes well before the
        // global deadline — forcing at least one retry cycle.
        ecs.submit(() -> {
            Thread.sleep(1150);
            return resultado("harvey");
        });

        List<ScrapeResult> onResultadoRecibidos = new CopyOnWriteArrayList<>();
        long deadlineGlobal = System.currentTimeMillis() + 10_000;

        List<ScrapeResult> resultados = SiteResultCollector.recolectar(
                ecs, 1, deadlineGlobal, TIMEOUT_POR_SITIO_S, GRANULARIDAD_MS, GRACE_MS,
                new AtomicBoolean(false), onResultadoRecibidos::add);

        assertThat(resultados)
                .as("a per-site timeout must retry the wait, not abandon the site — "
                    + "this is exactly what left Harvey RUNNING forever in scrape_run_site")
                .extracting(ScrapeResult::sitio)
                .containsExactly("harvey");
        assertThat(onResultadoRecibidos)
                .as("the callback fires as the result arrives, not buffered to the end")
                .extracting(ScrapeResult::sitio)
                .containsExactly("harvey");
    }

    @Test
    @DisplayName("the global deadline still stops the collection, leaving the missing site out")
    void elDeadlineGlobalSiTermina() throws Exception {
        var ecs = completionService();
        ecs.submit(() -> resultado("rapido"));
        ecs.submit(() -> {
            Thread.sleep(60_000); // never finishes inside the test
            return resultado("nunca-termina");
        });

        // Global remaining is floored to whole seconds (production budget:
        // 45 real minutes), so this needs to be comfortably over 1s for
        // "rapido" to get one per-site window at all, and under 2s so the
        // second site's window runs out before a second one opens.
        long deadlineGlobal = System.currentTimeMillis() + 1_200;

        List<ScrapeResult> resultados = SiteResultCollector.recolectar(
                ecs, 2, deadlineGlobal, TIMEOUT_POR_SITIO_S, GRANULARIDAD_MS, GRACE_MS,
                new AtomicBoolean(false), r -> { });

        assertThat(resultados)
                .as("the global deadline is the only thing allowed to leave a site uncollected")
                .extracting(ScrapeResult::sitio)
                .containsExactly("rapido");
    }

    @Test
    @DisplayName("a site whose task threw still counts as answered — no waiting for a result that never comes")
    void unSitioQueExplotaCuentaComoRespondido() {
        var ecs = completionService();
        ecs.submit(() -> { throw new OutOfMemoryError("boom"); });

        long t0 = System.currentTimeMillis();
        List<ScrapeResult> resultados = SiteResultCollector.recolectar(
                ecs, 1, System.currentTimeMillis() + 5_000, TIMEOUT_POR_SITIO_S, GRANULARIDAD_MS, GRACE_MS,
                new AtomicBoolean(false), r -> { });

        assertThat(resultados).isEmpty();
        assertThat(System.currentTimeMillis() - t0).isLessThan(2_000);
    }

    @Test
    @DisplayName("an interrupt stops the collection instead of spinning until the deadline")
    void unaInterrupcionCorta() {
        var ecs = completionService();
        ecs.submit(() -> {
            Thread.sleep(60_000);
            return resultado("nunca-termina");
        });

        Thread.currentThread().interrupt();
        long t0 = System.currentTimeMillis();
        try {
            SiteResultCollector.recolectar(
                    ecs, 1, System.currentTimeMillis() + 5_000, TIMEOUT_POR_SITIO_S, GRANULARIDAD_MS, GRACE_MS,
                    new AtomicBoolean(false), r -> { });
            assertThat(System.currentTimeMillis() - t0).isLessThan(2_000);
            assertThat(Thread.currentThread().isInterrupted()).as("the interrupt is preserved for the caller").isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("a cancellation stops the collection immediately")
    void unaCancelacionCorta() throws Exception {
        var ecs = completionService();
        ecs.submit(() -> {
            Thread.sleep(60_000);
            return resultado("nunca-termina");
        });

        AtomicBoolean cancelado = new AtomicBoolean(true);
        long deadlineGlobal = System.currentTimeMillis() + 30_000;

        long t0 = System.currentTimeMillis();
        List<ScrapeResult> resultados = SiteResultCollector.recolectar(
                ecs, 1, deadlineGlobal, TIMEOUT_POR_SITIO_S, GRANULARIDAD_MS, GRACE_MS,
                cancelado, r -> { });
        long transcurrido = System.currentTimeMillis() - t0;

        assertThat(resultados).isEmpty();
        assertThat(transcurrido).isLessThan(2_000);
    }
}
