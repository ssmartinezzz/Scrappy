package ar.scraper.web;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The run stopped itself, as opposed to the user cancelling it: kept apart from
 * {@code ScraperService.cancelado}, which the status view reports as a user cancellation.
 */
final class ParadaDeCorrida {

    private final AtomicBoolean detenida = new AtomicBoolean(false);

    void rearmar() {
        detenida.set(false);
    }

    boolean detenida() {
        return detenida.get();
    }

    /**
     * Raises the flag, stops the pool and closes the browsers. A failing step is attached to
     * {@code causa} and the next step still runs, so cleanup can never mask the original failure.
     */
    void detener(Throwable causa, ExecutorService pool, Runnable cerrarNavegadores) {
        detenida.set(true);
        if (pool != null) {
            try {
                pool.shutdownNow();
            } catch (Throwable t) {
                causa.addSuppressed(t);
            }
        }
        try {
            cerrarNavegadores.run();
        } catch (Throwable t) {
            causa.addSuppressed(t);
        }
    }
}
