package ar.scraper.security.reset;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Three sliding windows over reset requests, consulted inside the async task and never on the
 * request thread.
 */
@Component
public class ResetRateLimiter {

    public static final int POR_DIRECCION_POR_HORA = 3;
    public static final int POR_IP_POR_HORA = 10;
    public static final int GLOBAL_POR_HORA = 100;

    private static final Duration VENTANA = Duration.ofHours(1);
    private static final Duration INTERVALO_LIMPIEZA = Duration.ofMinutes(1);
    private static final String CLAVE_GLOBAL = "\0global";

    private final Clock reloj;
    private final Map<String, Deque<Instant>> ventanas = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> ultimaLimpieza = new AtomicReference<>(Instant.MIN);

    public ResetRateLimiter(Clock reloj) {
        this.reloj = reloj;
    }

    public boolean permitir(String direccion, String ip) {
        Instant ahora = reloj.instant();
        desalojarVencidas(ahora);
        // Every counter is consumed, not short-circuited: an attacker must not be able to keep
        // their IP budget intact by tripping the address limit first.
        boolean direccionOk = registrar("d:" + hash(direccion), POR_DIRECCION_POR_HORA, ahora);
        boolean ipOk = registrar("i:" + (ip == null ? "?" : ip), POR_IP_POR_HORA, ahora);
        boolean globalOk = registrar(CLAVE_GLOBAL, GLOBAL_POR_HORA, ahora);
        return direccionOk && ipOk && globalOk;
    }

    /**
     * Todo pasa adentro de {@code compute}: el candado por bin del mapa es la única sincronización
     * que hace falta, y es el mismo que usa el desalojo, así que no puede borrarse una ventana que
     * otro hilo está por escribir.
     */
    private boolean registrar(String clave, int tope, Instant ahora) {
        boolean[] admitido = { false };
        ventanas.compute(clave, (k, ventana) -> {
            Deque<Instant> v = podar(ventana, ahora);
            if (v.size() < tope) {
                v.addLast(ahora);
                admitido[0] = true;
            }
            return v.isEmpty() ? null : v;
        });
        return admitido[0];
    }

    /**
     * Sin esto sólo se poda la clave que vuelve a consultarse, y la que no vuelve —que es justo la
     * que fabrica quien recorre direcciones— no se poda nunca.
     */
    private void desalojarVencidas(Instant ahora) {
        Instant previa = ultimaLimpieza.get();
        if (ahora.isBefore(previa.plus(INTERVALO_LIMPIEZA))) return;
        // Un solo hilo barre; el resto sigue de largo en vez de hacer la misma pasada tres veces.
        if (!ultimaLimpieza.compareAndSet(previa, ahora)) return;
        for (String clave : ventanas.keySet()) {
            ventanas.computeIfPresent(clave, (k, v) -> podar(v, ahora).isEmpty() ? null : v);
        }
    }

    private Deque<Instant> podar(Deque<Instant> ventana, Instant ahora) {
        if (ventana == null) return new ArrayDeque<>();
        Instant corte = ahora.minus(VENTANA);
        while (!ventana.isEmpty() && ventana.peekFirst().isBefore(corte)) {
            ventana.pollFirst();
        }
        return ventana;
    }

    /** Sólo para tests. */
    int clavesEnMemoria() {
        return ventanas.size();
    }

    private static String hash(String direccion) {
        return direccion == null ? "?" : Integer.toHexString(direccion.toLowerCase().hashCode());
    }
}
