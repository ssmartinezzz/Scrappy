package ar.scraper.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ventana deslizante de <b>fallos</b> de login, por cuenta y global.
 *
 * <p>Sin esto el único freno de {@code POST /api/auth/login} es el costo de
 * Argon2id: ~76 ms, o sea ~13 intentos por segundo por core. Eso es un peaje,
 * no un límite.</p>
 *
 * <p>No hay clave por IP a propósito. {@code getRemoteAddr()} devuelve la IP
 * del proxy en cuanto haya uno adelante, y ahí todos los clientes caen en el
 * mismo balde sin que nada falle. Cuando exista ese proxy y se sepa cuál es,
 * la clave por IP se agrega con una allowlist de proxies de confianza —
 * confiar en {@code X-Forwarded-For} a ciegas es un agujero de spoofing.</p>
 */
@Component
public class LoginRateLimiter {

    public static final int FALLOS_POR_CUENTA = 5;
    public static final int FALLOS_GLOBALES = 100;

    /**
     * Corta a propósito, contra la hora que usa {@code ResetRateLimiter}: acá
     * el que quema los fallos de tu cuenta te deja afuera de tu propia
     * herramienta hasta que la ventana pase.
     *
     * <p>Los dos techos y esta ventana son propuestas, no mediciones — este
     * despliegue todavía no tiene tráfico real. Están flojos a propósito: un
     * límite que una persona real toca por accidente se termina apagando, y
     * apagarlo apaga también la protección.</p>
     */
    public static final Duration VENTANA = Duration.ofMinutes(15);

    private static final String GLOBAL = "\0global";

    private final Clock reloj;
    private final Map<String, Deque<Instant>> fallos = new ConcurrentHashMap<>();

    public LoginRateLimiter(Clock reloj) {
        this.reloj = reloj;
    }

    public boolean permitir(String username) {
        return vigentes(claveDe(username)) < FALLOS_POR_CUENTA
            && vigentes(GLOBAL) < FALLOS_GLOBALES;
    }

    /**
     * Se llama con el username que se envió, exista o no la cuenta: contar sólo
     * las reales convertiría el 429 en un oráculo de qué cuentas existen, que es
     * justo lo que {@code AuthEndpoints} evita devolviendo siempre el mismo 401
     * y pagando el costo de Argon2id incluso contra un hash señuelo.
     */
    public void registrarFallo(String username) {
        anotar(claveDe(username));
        anotar(GLOBAL);
    }

    /**
     * Sólo la cuenta, nunca el techo global: si el éxito lo limpiara, alguien
     * con una credencial válida podría resetear el presupuesto de todos entre
     * tanda y tanda de adivinanzas.
     */
    public void limpiarCuenta(String username) {
        fallos.remove(claveDe(username));
    }

    /** Cuántas claves ocupa el limiter ahora mismo. Sólo para tests. */
    int cuentasEnMemoria() {
        return fallos.size();
    }

    private void anotar(String clave) {
        fallos.compute(clave, (k, ventana) -> {
            Deque<Instant> vigentes = podar(ventana);
            vigentes.addLast(reloj.instant());
            return vigentes;
        });
    }

    private int vigentes(String clave) {
        // Devolver null desde computeIfPresent BORRA la clave: sin eso, una
        // clave por username intentado se acumula para siempre, y el username
        // lo elige quien ataca.
        Deque<Instant> ventana = fallos.computeIfPresent(clave, (k, v) -> {
            Deque<Instant> podada = podar(v);
            return podada.isEmpty() ? null : podada;
        });
        return ventana == null ? 0 : ventana.size();
    }

    private Deque<Instant> podar(Deque<Instant> ventana) {
        if (ventana == null) return new ArrayDeque<>();
        Instant corte = reloj.instant().minus(VENTANA);
        while (!ventana.isEmpty() && ventana.peekFirst().isBefore(corte)) {
            ventana.pollFirst();
        }
        return ventana;
    }

    /** Hasheada: si no, este mapa es la lista de quién intentó entrar recién. */
    private static String claveDe(String username) {
        String normalizado = username == null ? "" : username.trim().toLowerCase();
        return "u:" + Integer.toHexString(normalizado.hashCode());
    }
}
