package ar.scraper.security.reset;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limiter's own unit tests. {@code PasswordResetFlowTest} exercises it
 * through the service against a real database, which is where the ceilings are
 * asserted; what cannot be seen from there is how much memory a refused request
 * leaves behind, because the map is per-instance and every test builds a fresh
 * one.
 */
@Epic("Security")
@Feature("Password reset")
@Story("reset requests are rate limited")
@DisplayName("ResetRateLimiter — tres ventanas, y ninguna se queda en memoria")
class ResetRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-08-27T12:00:00Z");

    /** El reloj del limiter avanza; un {@code Clock.fixed} no puede ver deslizar la ventana. */
    private static final class RelojMovil extends Clock {
        private Instant ahora = T0;

        void avanzar(Duration d) { ahora = ahora.plus(d); }

        @Override public ZoneId getZone()                { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona)     { return this; }
        @Override public Instant instant()               { return ahora; }
    }

    @Test
    @DisplayName("Mil direcciones distintas no dejan mil claves cuando su ventana venció")
    void lasClavesVencidasNoSobreviven() {
        // El endpoint es PÚBLICO y sin credencial, y quien llama elige la
        // dirección. Una clave por dirección intentada que nunca se desaloja es
        // crecimiento sin techo desde afuera — y el tope global no lo acota:
        // una request rechazada crea su clave igual.
        RelojMovil reloj = new RelojMovil();
        ResetRateLimiter limiter = new ResetRateLimiter(reloj);

        for (int i = 0; i < 500; i++) {
            limiter.permitir("inventada-" + i + "@example.com", "10.0.0." + i);
        }
        assertThat(limiter.clavesEnMemoria())
                .as("dentro de la ventana las claves tienen que estar: son el límite")
                .isGreaterThan(500);

        reloj.avanzar(Duration.ofHours(2));
        limiter.permitir("otra@example.com", "10.1.1.1");

        assertThat(limiter.clavesEnMemoria())
                .as("pasada la ventana sólo quedan las de la última request y el contador global")
                .isLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Pasada la ventana la dirección vuelve a tener presupuesto")
    void laVentanaDesliza() {
        RelojMovil reloj = new RelojMovil();
        ResetRateLimiter limiter = new ResetRateLimiter(reloj);

        for (int i = 0; i < ResetRateLimiter.POR_DIRECCION_POR_HORA; i++) {
            assertThat(limiter.permitir("ana@example.com", "1.2.3.4")).isTrue();
        }
        assertThat(limiter.permitir("ana@example.com", "1.2.3.4")).isFalse();

        reloj.avanzar(Duration.ofHours(1).plusSeconds(1));
        assertThat(limiter.permitir("ana@example.com", "1.2.3.4"))
                .as("desalojar no puede ser lo mismo que olvidar el límite antes de tiempo")
                .isTrue();
    }

    @Test
    @DisplayName("Colmar la dirección igual consume el presupuesto de la IP")
    void todosLosContadoresSeConsumen() {
        // Invariante documentado del limiter: no hay short-circuit. Si colmar la
        // dirección dejara la IP intacta, se recorre una lista de direcciones
        // gratis. Lo fija acá porque el desalojo se mete justo en ese camino.
        RelojMovil reloj = new RelojMovil();
        ResetRateLimiter limiter = new ResetRateLimiter(reloj);

        for (int i = 0; i < ResetRateLimiter.POR_IP_POR_HORA; i++) {
            limiter.permitir("ana@example.com", "1.2.3.4");
        }
        assertThat(limiter.permitir("recien-llegada@example.com", "1.2.3.4"))
                .as("la IP gastó su presupuesto aun cuando la dirección venía rechazada")
                .isFalse();
    }

    @Test
    @DisplayName("Una dirección o IP nula no tumba el limiter")
    void unaDireccionNulaNoTumbaNada() {
        ResetRateLimiter limiter = new ResetRateLimiter(new RelojMovil());
        assertThat(limiter.permitir(null, null)).isTrue();
    }
}
