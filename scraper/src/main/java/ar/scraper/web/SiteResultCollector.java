package ar.scraper.web;

import ar.scraper.model.ScrapeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The exit condition is "every site answered", never "N waits elapsed": a per-site timeout retries
 * the same wait.
 */
final class SiteResultCollector {

    private static final Logger RUN_LOG = LoggerFactory.getLogger("ar.scraper.run");

    private SiteResultCollector() { }

    static List<ScrapeResult> recolectar(
            ExecutorCompletionService<ScrapeResult> ecs, int totalSitios,
            long deadlineGlobalMs, long timeoutPorSitioS, long granularidadMs, long graceMs,
            AtomicBoolean cancelado, Consumer<ScrapeResult> onResultado) {
        List<ScrapeResult> resultados = new ArrayList<>();
        // A task that threw instead of returning never yields a result, but it did answer.
        int respondidos = 0;

        while (respondidos < totalSitios) {
            long remaining = (deadlineGlobalMs - System.currentTimeMillis()) / 1000;
            long wait = Math.min(timeoutPorSitioS, remaining);

            if (wait <= 0) break;

            try {
                long deadlineSitio = System.currentTimeMillis() + wait * 1000L;
                Future<ScrapeResult> f = ScraperService.esperarResultado(
                        ecs, deadlineSitio, granularidadMs, cancelado);

                if (cancelado.get()) {
                    RUN_LOG.warn("[CANCEL]  Cancelación pedida — se deja de esperar sitios");
                    break;
                }
                if (f == null) {
                    f = ecs.poll(graceMs, TimeUnit.MILLISECONDS);
                    if (f == null) {
                        RUN_LOG.warn("[ESPERA]  Sin respuesta en {}s, reintentando...", wait);
                        continue;
                    }
                }

                respondidos++;
                ScrapeResult r = f.get();
                resultados.add(r);
                onResultado.accept(r);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                RUN_LOG.warn("Interrumpido esperando resultados");
                break;
            } catch (Exception e) {
                RUN_LOG.warn("Error en completionService: {}", e.getMessage());
            }
        }

        // Sites that finished while the collector was busy (onResultado runs serially and does
        // DB work) sit in the queue. Leaving them there would report a completed site as cut.
        if (!cancelado.get() && !Thread.currentThread().isInterrupted()) {
            drenarTerminados(ecs, resultados, onResultado);
        }
        return resultados;
    }

    private static void drenarTerminados(
            ExecutorCompletionService<ScrapeResult> ecs, List<ScrapeResult> resultados,
            Consumer<ScrapeResult> onResultado) {
        int drenados = 0;
        Future<ScrapeResult> f;
        while ((f = ecs.poll()) != null) {
            drenados++;
            try {
                ScrapeResult r = f.get();
                resultados.add(r);
                onResultado.accept(r);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                RUN_LOG.warn("Error en completionService: {}", e.getMessage());
            }
        }
        if (drenados > 0) {
            RUN_LOG.info("[DEADLINE] {} sitio(s) ya habian terminado y se recogieron", drenados);
        }
    }
}
