package ar.scraper.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A per-key counter over a sliding window, shared by every limiter that only needs "at most N per
 * key per window". Memory is bounded by the keys seen inside the window: expired ones are swept, so
 * a caller who invents keys cannot grow the map without limit.
 */
public final class VentanaDeslizante {

    private static final Duration INTERVALO_LIMPIEZA = Duration.ofMinutes(1);

    private final Clock reloj;
    private final Duration ventana;
    private final Map<String, Deque<Instant>> ventanas = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> ultimaLimpieza = new AtomicReference<>(Instant.MIN);

    public VentanaDeslizante(Clock reloj, Duration ventana) {
        this.reloj = reloj;
        this.ventana = ventana;
    }

    /** Consumes one slot of {@code clave}; false when its window already holds {@code tope}. */
    public boolean registrar(String clave, int tope) {
        Instant ahora = reloj.instant();
        desalojarVencidas(ahora);
        boolean[] admitido = { false };
        // Everything happens inside compute: the bin lock is the only synchronisation needed, and it is
        // the same one the sweep takes, so a window another thread is about to write is never removed.
        ventanas.compute(clave, (k, actual) -> {
            Deque<Instant> v = podar(actual, ahora);
            if (v.size() < tope) {
                v.addLast(ahora);
                admitido[0] = true;
            }
            return v.isEmpty() ? null : v;
        });
        return admitido[0];
    }

    /** For tests. */
    public int claves() {
        return ventanas.size();
    }

    private void desalojarVencidas(Instant ahora) {
        Instant previa = ultimaLimpieza.get();
        if (ahora.isBefore(previa.plus(INTERVALO_LIMPIEZA))) return;
        // One thread sweeps; the rest carry on instead of making the same pass again.
        if (!ultimaLimpieza.compareAndSet(previa, ahora)) return;
        for (String clave : ventanas.keySet()) {
            ventanas.computeIfPresent(clave, (k, v) -> podar(v, ahora).isEmpty() ? null : v);
        }
    }

    private Deque<Instant> podar(Deque<Instant> actual, Instant ahora) {
        if (actual == null) return new ArrayDeque<>();
        Instant corte = ahora.minus(ventana);
        while (!actual.isEmpty() && actual.peekFirst().isBefore(corte)) {
            actual.pollFirst();
        }
        return actual;
    }
}
