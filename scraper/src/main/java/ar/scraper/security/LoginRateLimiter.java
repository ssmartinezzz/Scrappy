package ar.scraper.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;

/**
 * Sin esto el único freno de {@code POST /api/auth/login} es el costo de Argon2id: ~22 ms, o sea
 * ~45 intentos por segundo por core.
 */
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    public static final int FALLOS_POR_CUENTA = 5;

    /** Por IP de origen, no global: un flood queda acotado a su propia IP, no frena al admin real. */
    public static final int FALLOS_POR_IP = 30;

    public static final Duration VENTANA = Duration.ofMinutes(15);

    private final Clock reloj;
    private final Map<String, Deque<Instant>> fallos = new ConcurrentHashMap<>();

    public boolean permitir(String username, String ip) {
        return vigentes(claveDe(username)) < FALLOS_POR_CUENTA
            && (ip == null || vigentes(claveIp(ip)) < FALLOS_POR_IP);
    }

    /**
     * Se llama con el username enviado exista o no la cuenta: contar sólo las reales convertiría el
     * 429 en un oráculo de qué cuentas existen.
     */
    public void registrarFallo(String username, String ip) {
        anotar(claveDe(username));
        if (ip != null) {
            anotar(claveIp(ip));
        }
    }

    /**
     * Sólo la cuenta, nunca el techo por IP: si el éxito lo limpiara, alguien con una credencial
     * válida podría resetear el presupuesto de su IP entre tanda y tanda de adivinanzas.
     */
    public void limpiarCuenta(String username) {
        fallos.remove(claveDe(username));
    }

    /** Sólo para tests. */
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
        // Devolver null desde computeIfPresent BORRA la clave: sin eso, una clave por username
        // intentado se acumula para siempre, y el username lo elige quien ataca.
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

    private static String claveIp(String ip) {
        return "ip:" + Integer.toHexString(ip.hashCode());
    }
}
