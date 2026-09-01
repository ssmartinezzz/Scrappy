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
 * <p><b>Y una hora es todo lo que dura.</b> Las claves vencidas se desalojan, y
 * eso no es prolijidad: el endpoint es público, sin credencial, y quien llama
 * elige la dirección y (con la IP) las dos claves que se crean. El tope global
 * no acota este mapa — una request rechazada por el tope crea su clave igual,
 * porque los tres contadores se consumen a propósito. Sin desalojo, cualquiera
 * desde afuera hace crecer memoria del proceso sin techo y para siempre,
 * mientras cada una de sus requests es correctamente rechazada.</p>
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
    private static final Duration INTERVALO_LIMPIEZA = Duration.ofMinutes(1);
    private static final String CLAVE_GLOBAL = "\0global";

    private final Clock reloj;
    private final Map<String, Deque<Instant>> ventanas = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> ultimaLimpieza = new AtomicReference<>(Instant.MIN);

    public ResetRateLimiter(Clock reloj) {
        this.reloj = reloj;
    }

    /**
     * @return {@code true} when this request may proceed to delivery. A
     *         {@code false} must still produce the same response to the caller.
     */
    public boolean permitir(String direccion, String ip) {
        Instant ahora = reloj.instant();
        desalojarVencidas(ahora);
        // Every counter is consumed, not short-circuited: an attacker must not be
        // able to keep their IP budget intact by tripping the address limit first.
        boolean direccionOk = registrar("d:" + hash(direccion), POR_DIRECCION_POR_HORA, ahora);
        boolean ipOk = registrar("i:" + (ip == null ? "?" : ip), POR_IP_POR_HORA, ahora);
        boolean globalOk = registrar(CLAVE_GLOBAL, GLOBAL_POR_HORA, ahora);
        return direccionOk && ipOk && globalOk;
    }

    /**
     * Todo pasa adentro de {@code compute}: el candado por bin del mapa es la
     * única sincronización que hace falta, y es el mismo que usa el desalojo, así
     * que no puede borrarse una ventana que otro hilo está por escribir. Devolver
     * null BORRA la clave — una ventana que quedó vacía no es un límite, es una
     * entrada de mapa.
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
     * Barre las claves cuya ventana entera venció. Sin esto sólo se poda la clave
     * que vuelve a consultarse, y la que no vuelve —que es justo la que fabrica
     * quien recorre direcciones— no se poda nunca.
     *
     * <p>Va con throttle porque el barrido es O(claves) y bajo una avalancha se
     * llama una vez por request: sin el intervalo, el costo de defenderse crece
     * al cuadrado con el ataque. Con él, el mapa retiene a lo sumo la ventana más
     * el intervalo, y drena solo.</p>
     */
    private void desalojarVencidas(Instant ahora) {
        Instant previa = ultimaLimpieza.get();
        if (ahora.isBefore(previa.plus(INTERVALO_LIMPIEZA))) return;
        // Un solo hilo barre; el resto sigue de largo en vez de hacer la misma
        // pasada tres veces.
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

    /** Cuántas claves ocupa el limiter ahora mismo. Sólo para tests. */
    int clavesEnMemoria() {
        return ventanas.size();
    }

    /** Keeps the map from becoming a list of who recently asked for a reset. */
    private static String hash(String direccion) {
        return direccion == null ? "?" : Integer.toHexString(direccion.toLowerCase().hashCode());
    }
}
