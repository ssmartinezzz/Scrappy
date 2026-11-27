package ar.scraper.web;

import ar.scraper.model.ScrapeResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SiteResultCollector — results already finished when the global deadline hits")
class SiteResultCollectorDeadlineDrainTest {

    private ExecutorService exec;
    private final CountDownLatch bloqueo = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        bloqueo.countDown();
        if (exec != null) exec.shutdownNow();
    }

    private static ScrapeResult resultado(String sitio) {
        return new ScrapeResult(sitio, List.of(), null, 0);
    }

    /** Two sites done and queued, one still running; the deadline is already behind us. */
    private ExecutorCompletionService<ScrapeResult> dosTerminadosUnoEnCurso() throws Exception {
        exec = Executors.newFixedThreadPool(3);
        var ecs = new ExecutorCompletionService<ScrapeResult>(exec);
        ecs.submit(() -> resultado("rockethard"));
        ecs.submit(() -> resultado("compragamer"));
        ecs.submit(() -> { bloqueo.await(); return resultado("city"); });
        Thread.sleep(300);
        return ecs;
    }

    @Test
    @DisplayName("a site that finished before the deadline is collected and sent through onResultado")
    void finishedSitesAreDrained() throws Exception {
        var ecs = dosTerminadosUnoEnCurso();
        List<String> vistos = Collections.synchronizedList(new ArrayList<>());

        List<ScrapeResult> out = SiteResultCollector.recolectar(
                ecs, 3, System.currentTimeMillis() - 1_000, 600, 20, 20,
                new AtomicBoolean(false), r -> vistos.add(r.sitio()));

        assertThat(out).extracting(ScrapeResult::sitio)
                .containsExactlyInAnyOrder("rockethard", "compragamer");
        assertThat(vistos).containsExactlyInAnyOrder("rockethard", "compragamer");
    }

    @Test
    @DisplayName("a site still running at the deadline is not collected")
    void runningSiteIsLeftAlone() throws Exception {
        var ecs = dosTerminadosUnoEnCurso();

        List<ScrapeResult> out = SiteResultCollector.recolectar(
                ecs, 3, System.currentTimeMillis() - 1_000, 600, 20, 20,
                new AtomicBoolean(false), r -> { });

        assertThat(out).extracting(ScrapeResult::sitio).doesNotContain("city");
    }

    @Test
    @DisplayName("a cancelled run drains nothing: the catalog must stay as it was")
    void cancelledRunDoesNotDrain() throws Exception {
        var ecs = dosTerminadosUnoEnCurso();

        List<ScrapeResult> out = SiteResultCollector.recolectar(
                ecs, 3, System.currentTimeMillis() - 1_000, 600, 20, 20,
                new AtomicBoolean(true), r -> { });

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("a throwing onResultado does not lose the other drained sites")
    void failingCallbackDoesNotStopTheDrain() throws Exception {
        var ecs = dosTerminadosUnoEnCurso();
        List<String> vistos = Collections.synchronizedList(new ArrayList<>());

        SiteResultCollector.recolectar(
                ecs, 3, System.currentTimeMillis() - 1_000, 600, 20, 20,
                new AtomicBoolean(false), r -> {
                    vistos.add(r.sitio());
                    throw new IllegalStateException("db down");
                });

        assertThat(vistos).hasSize(2);
        assertThat(exec.awaitTermination(0, TimeUnit.MILLISECONDS)).isFalse();
    }
}
