package ar.scraper.security;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Security")
@Feature("Authentication")
@Story("login is rate limited")
@DisplayName("LoginRateLimiter — cuenta fallos, no intentos")
class LoginRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-08-27T12:00:00Z");

    private LoginRateLimiter enT0() {
        return new LoginRateLimiter(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Cien logins exitosos no consumen presupuesto")
    void elExitoNoConsumePresupuesto() {
        LoginRateLimiter limiter = enT0();
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.permitir("ana")).isTrue();
            limiter.limpiarCuenta("ana");
        }
        assertThat(limiter.permitir("ana")).isTrue();
    }

    @Test
    @DisplayName("El fallo número FALLOS_POR_CUENTA frena la cuenta")
    void alColmarLaCuentaSeFrena() {
        LoginRateLimiter limiter = enT0();
        for (int i = 0; i < LoginRateLimiter.FALLOS_POR_CUENTA; i++) {
            assertThat(limiter.permitir("ana")).as("intento %d", i + 1).isTrue();
            limiter.registrarFallo("ana");
        }
        assertThat(limiter.permitir("ana")).isFalse();
    }

    @Test
    @DisplayName("Frenar a una cuenta no frena a otra")
    void elFrenoEsPorCuenta() {
        LoginRateLimiter limiter = enT0();
        colmarCuenta(limiter, "ana");

        assertThat(limiter.permitir("ana")).isFalse();
        assertThat(limiter.permitir("beto")).isTrue();
    }

    @Test
    @DisplayName("Un usuario inexistente se cuenta igual: el 429 no delata qué cuentas existen")
    void elFrenoNoEsUnOraculoDeExistencia() {
        LoginRateLimiter limiter = enT0();
        colmarCuenta(limiter, "esta-cuenta-no-existe");

        assertThat(limiter.permitir("esta-cuenta-no-existe")).isFalse();
    }

    @Test
    @DisplayName("Un login exitoso limpia su cuenta y nunca el techo global")
    void elExitoNoLimpiaElTechoGlobal() {
        LoginRateLimiter limiter = enT0();
        for (int i = 0; i < LoginRateLimiter.FALLOS_GLOBALES; i++) {
            limiter.registrarFallo("victima-" + i);
        }
        assertThat(limiter.permitir("cualquiera")).isFalse();

        limiter.limpiarCuenta("la-cuenta-que-si-tengo");

        assertThat(limiter.permitir("cualquiera")).isFalse();
    }

    @Test
    @DisplayName("Pasada la ventana los fallos viejos ya no cuentan")
    void laVentanaDesliza() {
        LoginRateLimiter limiter = enT0();
        colmarCuenta(limiter, "ana");
        assertThat(limiter.permitir("ana")).isFalse();

        Instant despues = T0.plus(LoginRateLimiter.VENTANA).plusSeconds(1);
        assertThat(new LoginRateLimiter(Clock.fixed(despues, ZoneOffset.UTC))
                .permitir("ana")).isTrue();
    }

    @Test
    @DisplayName("Una cuenta que se vacía deja de ocupar memoria")
    void laCuentaVaciaNoQuedaEnMemoria() {
        // Un atacante controla el username, así que una clave por username
        // intentado que nunca se desaloja es crecimiento sin techo.
        LoginRateLimiter limiter = enT0();
        for (int i = 0; i < 500; i++) {
            limiter.registrarFallo("inventada-" + i);
            limiter.limpiarCuenta("inventada-" + i);
        }
        assertThat(limiter.cuentasEnMemoria())
                .as("500 usernames distintos no pueden dejar 500 claves; sólo sobrevive el contador global")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Un username nulo o vacío no tumba el limiter")
    void unUsernameNuloNoTumbaNada() {
        LoginRateLimiter limiter = enT0();
        assertThat(limiter.permitir(null)).isTrue();
        limiter.registrarFallo(null);
        limiter.limpiarCuenta(null);
        assertThat(limiter.permitir("")).isTrue();
    }

    private void colmarCuenta(LoginRateLimiter limiter, String username) {
        for (int i = 0; i < LoginRateLimiter.FALLOS_POR_CUENTA; i++) {
            limiter.registrarFallo(username);
        }
    }
}
