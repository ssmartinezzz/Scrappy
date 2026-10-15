package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.notificaciones.PreferenciaAvisosPort;
import ar.scraper.notificaciones.TokenBajaAvisos;
import ar.scraper.web.support.Wire;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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

    @BeforeEach
    void setUp() {
        preferencias = mock(PreferenciaAvisosPort.class);
        tokens = new TokenBajaAvisos("un-secreto-de-prueba-de-al-menos-32-bytes!!");
        controller = new BajaAvisosController(tokens, preferencias);
    }

    @Test
    @DisplayName("POST with a valid token turns the account's alerts off")
    void daDeBaja() {
        UUID id = UUID.randomUUID();
        when(preferencias.actualizar(id, false)).thenReturn(true);

        var resp = controller.darDeBaja(tokens.emitir(id));

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

        var deReal = controller.darDeBaja(tokens.emitir(real));
        var deDesconocida = controller.darDeBaja(tokens.emitir(desconocida));
        var deFirmaAjena = controller.darDeBaja(firmaAjena);

        assertThat(deDesconocida.getStatusCode()).isEqualTo(deReal.getStatusCode());
        assertThat(deFirmaAjena.getStatusCode()).isEqualTo(deReal.getStatusCode());
        assertThat(Wire.body(deDesconocida)).isEqualTo(Wire.body(deReal));
        assertThat(Wire.body(deFirmaAjena)).isEqualTo(Wire.body(deReal));
    }

    @Test
    @DisplayName("a wrong signature never reaches the database")
    void firmaAjenaNoEscribe() {
        controller.darDeBaja(new TokenBajaAvisos("otro-secreto-distinto-de-al-menos-32-bytes!").emitir(UUID.randomUUID()));

        verifyNoInteractions(preferencias);
    }

    @Test
    @DisplayName("POST is idempotent: the second call answers the same and leaves the account unsubscribed")
    void idempotente() {
        UUID id = UUID.randomUUID();
        String token = tokens.emitir(id);

        var primera = controller.darDeBaja(token);
        var segunda = controller.darDeBaja(token);

        assertThat(Wire.body(segunda)).isEqualTo(Wire.body(primera));
        verify(preferencias, times(2)).actualizar(id, false);
    }

    @Test
    @DisplayName("a token that is not even shaped like ours is 400, on POST and on GET")
    void malformado() {
        assertThatThrownBy(() -> controller.darDeBaja("no-es-un-token"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> controller.confirmar("<script>"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(preferencias, never()).actualizar(any(), anyBoolean());
    }

    @Test
    @DisplayName("GET changes nothing (mail scanners prefetch links): it serves a page whose button POSTs")
    void getNoCambiaNada() {
        UUID id = UUID.randomUUID();

        var resp = controller.confirmar(tokens.emitir(id));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_HTML);
        assertThat(resp.getBody()).containsIgnoringCase("method=\"post\"").doesNotContain(id.toString());
        verifyNoInteractions(preferencias);
    }

    @Test
    @DisplayName("the GET page is the same for a real token and a forged one, and is never cached")
    void getNoDistingue() {
        var real = controller.confirmar(tokens.emitir(UUID.randomUUID()));
        var falso = controller.confirmar(new TokenBajaAvisos("otro-secreto-distinto-de-al-menos-32-bytes!").emitir(UUID.randomUUID()));

        assertThat(falso.getBody()).isEqualTo(real.getBody());
        assertThat(real.getHeaders().getCacheControl()).contains("no-store");
    }
}
