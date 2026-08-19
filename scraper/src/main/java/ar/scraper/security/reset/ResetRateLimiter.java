package ar.scraper.security.reset;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Three sliding windows over reset requests, consulted <b>inside</b> the async
 * task and never on the request thread.
 *
 * <p>That placement is not an implementation detail. A limiter consulted before
 * responding would make a limited request measurably different from an
 * unlimited one, which hands back the account-existence signal the uniform 202
 * exists to hide. For the same reason a limited request still gets the same 202:
 * a 429 would be a per-account oracle — ask twice, and the second answer tells
 * you the address is real.</p>
 *
 * <p><b>Three keys, three different attacks:</b></p>
 * <ul>
 *   <li><b>Per address (3/h)</b> — stops someone mail-bombing one person's inbox
 *       by hammering the form with their address.</li>
 *   <li><b>Per IP (10/h)</b> — stops one source walking a list of addresses to
 *       learn which ones exist by volume of delivered mail.</li>
 *   <li><b>Global (100/h)</b> — a backstop for the provider's own daily cap.
 *       Blowing through Gmail's ~500/day gets the account throttled, which
 *       breaks resets for everybody.</li>
 * </ul>
 *
 * <p>The address is hashed before being used as a key: this map would otherwise
 * be a list of everyone who has recently asked for a reset, sitting in memory
 * for an hour.</p>
 *
 * <p><b>The three ceilings are proposals, not measurements.</b> Validating them
 * needs real traffic, and this deployment has none yet. They are deliberately
 * loose enough that a real person cannot hit them by accident.</p>
 */
@Component
public class ResetRateLimiter {

    public static final int POR_DIRECCION_POR_HORA = 3;
    public static final int POR_IP_POR_HORA = 10;
    public static final int GLOBAL_POR_HORA = 100;

    private static final Duration VENTANA = Duration.ofHours(1);
    private static final String CLAVE_GLOBAL = "\0global";

    private final Clock reloj;
    private final Map<String, Deque<Instant>> ventanas = new ConcurrentHashMap<>();

    public ResetRateLimiter(Clock reloj) {
        this.reloj = reloj;
    }

    /**
     * @return {@code true} when this request may proceed to delivery. A
     *         {@code false} must still produce the same response to the caller.
     */
    public boolean permitir(String direccion, String ip) {
        Instant ahora = reloj.instant();
        // Every counter is consumed, not short-circuited: an attacker must not be
        // able to keep their IP budget intact by tripping the address limit first.
        boolean direccionOk = registrar("d:" + hash(direccion), POR_DIRECCION_POR_HORA, ahora);
        boolean ipOk = registrar("i:" + (ip == null ? "?" : ip), POR_IP_POR_HORA, ahora);
        boolean globalOk = registrar(CLAVE_GLOBAL, GLOBAL_POR_HORA, ahora);
        return direccionOk && ipOk && globalOk;
    }

    private boolean registrar(String clave, int tope, Instant ahora) {
        Deque<Instant> ventana = ventanas.computeIfAbsent(clave, k -> new ArrayDeque<>());
        synchronized (ventana) {
            Instant corte = ahora.minus(VENTANA);
            while (!ventana.isEmpty() && ventana.peekFirst().isBefore(corte)) {
                ventana.pollFirst();
            }
            if (ventana.size() >= tope) {
                return false;
            }
            ventana.addLast(ahora);
            return true;
        }
    }

    /** Keeps the map from becoming a list of who recently asked for a reset. */
    private static String hash(String direccion) {
        return direccion == null ? "?" : Integer.toHexString(direccion.toLowerCase().hashCode());
    }
}
