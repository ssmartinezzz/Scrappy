package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.api.ApiResponse;
import ar.scraper.web.dto.MensajeDto;
import ar.scraper.notificaciones.PreferenciaAvisosPort;
import ar.scraper.notificaciones.TokenBajaAvisos;
import ar.scraper.web.support.Wire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("BajaAvisosController — one-click unsubscribe (RFC 8058), public and never an oracle")
class BajaAvisosControllerTest {

    private PreferenciaAvisosPort preferencias;
    private TokenBajaAvisos tokens;
    private BajaAvisosController controller;
    private final RelojMovil reloj = new RelojMovil();

    private static final class RelojMovil extends Clock {
        private Instant ahora = Instant.parse("2026-10-14T12:00:00Z");

        void avanzar(Duration d) { ahora = ahora.plus(d); }

        @Override public ZoneId getZone()            { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant()           { return ahora; }
    }

    private ResponseEntity<ApiResponse<MensajeDto>> baja(String token) {
        return baja(token, "203.0.113.7");
    }

    private ResponseEntity<ApiResponse<MensajeDto>> baja(String token, String ip) {
        return controller.darDeBaja(token, desde(ip));
    }

    private ResponseEntity<String> pagina(String token) {
        return controller.confirmar(token, desde("203.0.113.7"));
    }

    private static MockHttpServletRequest desde(String ip) {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setRemoteAddr(ip);
        return peticion;
    }

    @BeforeEach
    void setUp() {
        preferencias = mock(PreferenciaAvisosPort.class);
        tokens = new TokenBajaAvisos("un-secreto-de-prueba-de-al-menos-32-bytes!!");
        controller = new BajaAvisosController(tokens, preferencias, reloj);
    }

    @Test
    @DisplayName("POST with a valid token turns the account's alerts off")
    void daDeBaja() {
        UUID id = UUID.randomUUID();
        when(preferencias.actualizar(id, false)).thenReturn(true);

        var resp = baja(tokens.emitir(id));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(preferencias).actualizar(id, false);
    }

    @Test
    @DisplayName("the answer is identical for a real account, an unknown one and a wrong signature: no enumeration")
    void mismaRespuestaSiempre() {
        UUID real = UUID.randomUUID();
        UUID desconocida = UUID.randomUUID();
        when(preferencias.actualizar(real, false)).thenReturn(true);
        when(preferencias.actualizar(desconocida, false)).thenReturn(false);
        String firmaAjena = new TokenBajaAvisos("otro-secreto-distinto-de-al-menos-32-bytes!").emitir(real);

        var deReal = baja(tokens.emitir(real));
        var deDesconocida = baja(tokens.emitir(desconocida));
        var deFirmaAjena = baja(firmaAjena);

        assertThat(deDesconocida.getStatusCode()).isEqualTo(deReal.getStatusCode());
        assertThat(deFirmaAjena.getStatusCode()).isEqualTo(deReal.getStatusCode());
        assertThat(Wire.body(deDesconocida)).isEqualTo(Wire.body(deReal));
        assertThat(Wire.body(deFirmaAjena)).isEqualTo(Wire.body(deReal));
    }

    @Test
    @DisplayName("a wrong signature never reaches the database")
    void firmaAjenaNoEscribe() {
        baja(new TokenBajaAvisos("otro-secreto-distinto-de-al-menos-32-bytes!").emitir(UUID.randomUUID()));

        verifyNoInteractions(preferencias);
    }

    @Test
    @DisplayName("POST is idempotent: the second call answers the same and leaves the account unsubscribed")
    void idempotente() {
        UUID id = UUID.randomUUID();
        String token = tokens.emitir(id);

        var primera = baja(token);
        var segunda = baja(token);

        assertThat(Wire.body(segunda)).isEqualTo(Wire.body(primera));
        verify(preferencias, times(2)).actualizar(id, false);
    }

    @Test
    @DisplayName("a token that is not even shaped like ours is 400, on POST and on GET")
    void malformado() {
        assertThatThrownBy(() -> baja("no-es-un-token"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> pagina("<script>"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(preferencias, never()).actualizar(any(), anyBoolean());
    }

    @Test
    @DisplayName("GET changes nothing (mail scanners prefetch links): it serves a page whose button POSTs")
    void getNoCambiaNada() {
        UUID id = UUID.randomUUID();

        var resp = pagina(tokens.emitir(id));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(resp.getBody()).containsIgnoringCase("method=\"post\"").doesNotContain(id.toString());
        verifyNoInteractions(preferencias);
    }

    @Test
    @DisplayName("the GET page is the same for a real token and a forged one, and is never cached")
    void getNoDistingue() {
        var real = pagina(tokens.emitir(UUID.randomUUID()));
        var falso = pagina(new TokenBajaAvisos("otro-secreto-distinto-de-al-menos-32-bytes!").emitir(UUID.randomUUID()));

        assertThat(falso.getBody()).isEqualTo(real.getBody());
        assertThat(real.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    @DisplayName("an IP gets 60 requests a minute; the 61st is 429 with Retry-After and never reaches the database, another IP is unaffected")
    void topePorIp() {
        String token = tokens.emitir(UUID.randomUUID());
        for (int i = 0; i < 60; i++) {
            baja(token, "198.51.100.9");
        }
        clearInvocations(preferencias);

        assertThatThrownBy(() -> baja(token, "198.51.100.9")).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(e.code()).isEqualTo("demasiados_pedidos");
            assertThat(e.headers().getFirst("Retry-After")).isEqualTo("60");
        });
        assertThatThrownBy(() -> controller.confirmar("<script>", desde("198.51.100.9")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).as("the throttle comes before token validation").isEqualTo("demasiados_pedidos"));
        verifyNoInteractions(preferencias);
        assertThat(baja(token, "198.51.100.10").getStatusCode().value()).isEqualTo(200);

        reloj.avanzar(Duration.ofSeconds(61));
        assertThat(baja(token, "198.51.100.9").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("the confirmation page carries a CSP that allows nothing but its own inline style and its own form")
    void paginaConCsp() {
        var resp = pagina(tokens.emitir(UUID.randomUUID()));

        assertThat(resp.getHeaders().getFirst("Content-Security-Policy"))
                .isEqualTo("default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'");
    }
}
