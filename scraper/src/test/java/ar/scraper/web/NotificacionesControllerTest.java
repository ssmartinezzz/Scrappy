package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.notificaciones.PreferenciaAvisos;
import ar.scraper.notificaciones.PreferenciaAvisosPort;
import ar.scraper.security.ActorResolver;
import ar.scraper.web.support.SujetoDePrueba;
import ar.scraper.web.support.Wire;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("NotificacionesController — each account reads and toggles only its own price-drop flag")
class NotificacionesControllerTest {

    private PreferenciaAvisosPort preferencias;
    private NotificacionesController controller;
    private UUID yo;

    @BeforeEach
    void setUp() {
        preferencias = mock(PreferenciaAvisosPort.class);
        controller = new NotificacionesController(preferencias, new ActorResolver());
        yo = SujetoDePrueba.entrar("VIEWER");
    }

    @AfterEach
    void limpiar() {
        SujetoDePrueba.salir();
    }

    @Test
    @DisplayName("GET returns the caller's flag and whether the account has an email")
    void lee() {
        when(preferencias.leer(yo)).thenReturn(Optional.of(new PreferenciaAvisos(false, true)));

        var data = Wire.data(controller.getPreferencia());

        assertThat(data.get("notificarBajasPrecio").asBoolean()).isFalse();
        assertThat(data.get("tieneEmail").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("PUT changes the caller's own flag and answers with the new state")
    void actualiza() {
        when(preferencias.actualizar(yo, false)).thenReturn(true);
        when(preferencias.leer(yo)).thenReturn(Optional.of(new PreferenciaAvisos(false, true)));

        var resp = controller.putPreferencia(Map.of("notificarBajasPrecio", false));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Wire.data(resp).get("notificarBajasPrecio").asBoolean()).isFalse();
        verify(preferencias).actualizar(yo, false);
    }

    @Test
    @DisplayName("PUT on an account with no email is 409: there is nothing to send to")
    void sinEmail() {
        when(preferencias.actualizar(yo, true)).thenReturn(false);

        assertThatThrownBy(() -> controller.putPreferencia(Map.of("notificarBajasPrecio", true)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("sin_email");
                });
    }

    @Test
    @DisplayName("PUT without a boolean is 400 and writes nothing, whatever else the body carries")
    void cuerpoInvalido() {
        for (Map<String, Object> body : new Map[]{Map.of(), Map.of("notificarBajasPrecio", "si"),
                Map.of("usuarioId", UUID.randomUUID().toString())}) {
            assertThatThrownBy(() -> controller.putPreferencia(body))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        verify(preferencias, never()).actualizar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("without an authenticated subject the endpoint refuses instead of picking an account")
    void sinSujeto() {
        SujetoDePrueba.salir();

        assertThatThrownBy(controller::getPreferencia).isInstanceOf(RuntimeException.class);
        verify(preferencias, never()).leer(org.mockito.ArgumentMatchers.any());
    }
}
