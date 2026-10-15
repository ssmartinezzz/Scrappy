package ar.scraper.security.reset;

import ar.scraper.security.VentanaDeslizante;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Three sliding windows over reset requests, consulted inside the async task and never on the
 * request thread.
 */
@Component
public class ResetRateLimiter {

    public static final int POR_DIRECCION_POR_HORA = 3;
    public static final int POR_IP_POR_HORA = 10;
    public static final int GLOBAL_POR_HORA = 100;

    private static final String CLAVE_GLOBAL = "\0global";

    private final VentanaDeslizante ventanas;

    public ResetRateLimiter(Clock reloj) {
        this.ventanas = new VentanaDeslizante(reloj, Duration.ofHours(1));
    }

    public boolean permitir(String direccion, String ip) {
        // Every counter is consumed, not short-circuited: an attacker must not be able to keep
        // their IP budget intact by tripping the address limit first.
        boolean direccionOk = ventanas.registrar("d:" + hash(direccion), POR_DIRECCION_POR_HORA);
        boolean ipOk = ventanas.registrar("i:" + (ip == null ? "?" : ip), POR_IP_POR_HORA);
        boolean globalOk = ventanas.registrar(CLAVE_GLOBAL, GLOBAL_POR_HORA);
        return direccionOk && ipOk && globalOk;
    }

    /** Sólo para tests. */
    int clavesEnMemoria() {
        return ventanas.claves();
    }

    private static String hash(String direccion) {
        return direccion == null ? "?" : Integer.toHexString(direccion.toLowerCase().hashCode());
    }
}
