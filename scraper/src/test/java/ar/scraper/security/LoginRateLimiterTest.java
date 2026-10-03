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
@Story("login is rate limited per account and per source IP")
@DisplayName("LoginRateLimiter — cuenta fallos, no intentos")
class LoginRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-08-27T12:00:00Z");
    private static final String IP = "203.0.113.7";
    private static final String OTRA_IP = "198.51.100.9";

    private LoginRateLimiter enT0() {
        return new LoginRateLimiter(Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Cien logins exitosos no consumen presupuesto")
    void elExitoNoConsumePresupuesto() {
        LoginRateLimiter limiter = enT0();
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.permitir("ana", IP)).isTrue();
            limiter.limpiarCuenta("ana");
        }
        assertThat(limiter.permitir("ana", IP)).isTrue();
    }

    @Test
    @DisplayName("El fallo número FALLOS_POR_CUENTA frena la cuenta")
    void alColmarLaCuentaSeFrena() {
        LoginRateLimiter limiter = enT0();
        for (int i = 0; i < LoginRateLimiter.FALLOS_POR_CUENTA; i++) {
            assertThat(limiter.permitir("ana", IP)).as("intento %d", i + 1).isTrue();
            limiter.registrarFallo("ana", IP);
        }
        assertThat(limiter.permitir("ana", IP)).isFalse();
    }

    @Test
    @DisplayName("Frenar a una cuenta no frena a otra")
    void elFrenoEsPorCuenta() {
        LoginRateLimiter limiter = enT0();
        colmarCuenta(limiter, "ana");

        assertThat(limiter.permitir("ana", IP)).isFalse();
        assertThat(limiter.permitir("beto", IP)).isTrue();
    }

    @Test
    @DisplayName("Un usuario inexistente se cuenta igual: el 429 no delata qué cuentas existen")
    void elFrenoNoEsUnOraculoDeExistencia() {
        LoginRateLimiter limiter = enT0();
        colmarCuenta(limiter, "esta-cuenta-no-existe");

        assertThat(limiter.permitir("esta-cuenta-no-existe", IP)).isFalse();
    }

    @Test
    @DisplayName("Un flood desde una IP frena a esa IP pero no a otra: el admin real entra desde otra")
    void elFloodDeUnaIpNoFrenaAOtra() {
        LoginRateLimiter limiter = enT0();
        for (int i = 0; i < LoginRateLimiter.FALLOS_POR_IP; i++) {
            limiter.registrarFallo("inventada-" + i, IP);
        }
        assertThat(limiter.permitir("admin", IP)).isFalse();
        assertThat(limiter.permitir("admin", OTRA_IP)).isTrue();
    }

    @Test
    @DisplayName("El éxito limpia la cuenta pero no el presupuesto de la IP")
    void elExitoNoLimpiaElTechoDeIp() {
        LoginRateLimiter limiter = enT0();
        for (int i = 0; i < LoginRateLimiter.FALLOS_POR_IP; i++) {
            limiter.registrarFallo("inventada-" + i, IP);
        }
        limiter.limpiarCuenta("la-cuenta-que-si-tengo");

        assertThat(limiter.permitir("cualquiera", IP)).isFalse();
    }

    @Test
    @DisplayName("Pasada la ventana los fallos viejos ya no cuentan")
    void laVentanaDesliza() {
        LoginRateLimiter limiter = enT0();
        colmarCuenta(limiter, "ana");
        assertThat(limiter.permitir("ana", IP)).isFalse();

        Instant despues = T0.plus(LoginRateLimiter.VENTANA).plusSeconds(1);
        assertThat(new LoginRateLimiter(Clock.fixed(despues, ZoneOffset.UTC))
                .permitir("ana", IP)).isTrue();
    }

    @Test
    @DisplayName("Una cuenta que se vacía deja de ocupar memoria")
    void laCuentaVaciaNoQuedaEnMemoria() {
        LoginRateLimiter limiter = enT0();
        for (int i = 0; i < 500; i++) {
            limiter.registrarFallo("inventada-" + i, IP);
            limiter.limpiarCuenta("inventada-" + i);
        }
        assertThat(limiter.cuentasEnMemoria())
                .as("500 usernames distintos desde una IP no dejan 500 claves; sólo sobrevive la de la IP")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Un username o IP nulos no tumban el limiter")
    void unUsernameNuloNoTumbaNada() {
        LoginRateLimiter limiter = enT0();
        assertThat(limiter.permitir(null, null)).isTrue();
        limiter.registrarFallo(null, null);
        limiter.limpiarCuenta(null);
        assertThat(limiter.permitir("", null)).isTrue();
    }

    private void colmarCuenta(LoginRateLimiter limiter, String username) {
        for (int i = 0; i < LoginRateLimiter.FALLOS_POR_CUENTA; i++) {
            limiter.registrarFallo(username, IP);
        }
    }
}
