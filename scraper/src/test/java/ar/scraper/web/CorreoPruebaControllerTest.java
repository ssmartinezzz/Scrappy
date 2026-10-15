package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.db.UsuarioRepository;
import ar.scraper.notificaciones.DiagnosticoSmtp;
import ar.scraper.notificaciones.EstadoSmtp;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CorreoPruebaController — an ADMIN sends a test mail and learns why it did not arrive")
class CorreoPruebaControllerTest {

    private DiagnosticoSmtp diagnostico;
    private UsuarioRepository usuarios;
    private CorreoPruebaController controller;

    @BeforeEach
    void setUp() {
        diagnostico = mock(DiagnosticoSmtp.class);
        usuarios = mock(UsuarioRepository.class);
        controller = new CorreoPruebaController(diagnostico, usuarios, new ActorResolver());
        SujetoDePrueba.entrar("ADMIN");
        when(usuarios.buscarActivaPorUsername("test-admin")).thenReturn(Optional.of(
                new UsuarioRepository.Cuenta(UUID.randomUUID(), "test-admin", "admin@example.com", "hash", false)));
    }

    @AfterEach
    void limpiar() {
        SujetoDePrueba.salir();
    }

    @Test
    @DisplayName("with no body it sends to the caller's own address and answers with the masked result")
    void aSiMismo() {
        when(diagnostico.enviarPrueba("admin@example.com")).thenReturn(EstadoSmtp.ok("Mail de prueba enviado a a**@example.com."));

        var resp = controller.enviar(null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(Wire.data(resp).get("mensaje").asText()).contains("a**@example.com").doesNotContain("admin@example.com");
    }

    @Test
    @DisplayName("a given destino is normalized and used instead of the caller's address")
    void aOtraDireccion() {
        when(diagnostico.enviarPrueba("otro@example.com")).thenReturn(EstadoSmtp.ok("enviado"));

        controller.enviar(Map.of("destino", " Otro@Example.com "));

        verify(diagnostico).enviarPrueba("otro@example.com");
    }

    @Test
    @DisplayName("a malformed destino is 400 and nothing is attempted")
    void destinoInvalido() {
        assertThatThrownBy(() -> controller.enviar(Map.of("destino", "sin-arroba")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("email_invalido"));
        verify(diagnostico, never()).enviarPrueba(anyString());
    }

    @Test
    @DisplayName("an ADMIN with no email and no destino gets 409 sin_destino")
    void sinDestino() {
        when(usuarios.buscarActivaPorUsername("test-admin")).thenReturn(Optional.of(
                new UsuarioRepository.Cuenta(UUID.randomUUID(), "test-admin", null, "hash", false)));

        assertThatThrownBy(() -> controller.enviar(null)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("sin_destino");
        });
        verify(diagnostico, never()).enviarPrueba(anyString());
    }

    @Test
    @DisplayName("SMTP not configured is 409 smtp_no_configurado")
    void noConfigurado() {
        when(diagnostico.enviarPrueba("admin@example.com")).thenReturn(EstadoSmtp.noConfigurado());

        assertThatThrownBy(() -> controller.enviar(null)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("smtp_no_configurado");
        });
    }

    @Test
    @DisplayName("a failure is 502 smtp_fallo carrying the reason and the hint")
    void fallo() {
        when(diagnostico.enviarPrueba("admin@example.com"))
                .thenReturn(EstadoSmtp.fallo("El servidor rechazó el usuario o la contraseña.", "Usá una contraseña de aplicación."));

        assertThatThrownBy(() -> controller.enviar(null)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(e.code()).isEqualTo("smtp_fallo");
            assertThat(e.getMessage()).contains("rechazó el usuario").contains("contraseña de aplicación");
        });
    }
}
