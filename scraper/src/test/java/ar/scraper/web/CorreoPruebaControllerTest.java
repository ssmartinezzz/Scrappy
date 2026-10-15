package ar.scraper.web;

import ar.scraper.api.ApiException;
import ar.scraper.db.UsuarioRepository;
import ar.scraper.notificaciones.DiagnosticoSmtp;
import ar.scraper.notificaciones.EstadoSmtp;
import ar.scraper.security.ActorResolver;
import ar.scraper.web.support.SujetoDePrueba;
import ar.scraper.web.support.Wire;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CorreoPruebaController — an ADMIN sends a test mail and learns why it did not arrive")
class CorreoPruebaControllerTest {

    private DiagnosticoSmtp diagnostico;
    private UsuarioRepository usuarios;
    private CorreoPruebaController controller;
    private final RelojMovil reloj = new RelojMovil();
    private final Logger logger = (Logger) LoggerFactory.getLogger(CorreoPruebaController.class);
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();

    private static final class RelojMovil extends Clock {
        private Instant ahora = Instant.parse("2026-10-14T12:00:00Z");

        void avanzar(Duration d) { ahora = ahora.plus(d); }

        @Override public ZoneId getZone()            { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant()           { return ahora; }
    }

    @BeforeEach
    void setUp() {
        diagnostico = mock(DiagnosticoSmtp.class);
        usuarios = mock(UsuarioRepository.class);
        controller = new CorreoPruebaController(diagnostico, usuarios, new ActorResolver(), reloj);
        SujetoDePrueba.entrar("ADMIN");
        log.start();
        logger.addAppender(log);
        when(usuarios.buscarActivaPorUsername("test-admin")).thenReturn(Optional.of(
                new UsuarioRepository.Cuenta(UUID.randomUUID(), "test-admin", "admin@example.com", "hash", false)));
    }

    @AfterEach
    void limpiar() {
        logger.detachAppender(log);
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

    @Test
    @DisplayName("an actor gets 5 test mails per hour; the 6th is 429 with Retry-After, nothing is sent, and it frees up an hour later")
    void tope() {
        when(diagnostico.enviarPrueba("otro@example.com")).thenReturn(EstadoSmtp.ok("enviado"));
        for (int i = 0; i < 5; i++) {
            controller.enviar(Map.of("destino", "otro@example.com"));
        }

        assertThatThrownBy(() -> controller.enviar(Map.of("destino", "otro@example.com")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.code()).isEqualTo("demasiados_envios");
                    assertThat(e.headers().getFirst("Retry-After")).isEqualTo("3600");
                });
        verify(diagnostico, times(5)).enviarPrueba("otro@example.com");

        reloj.avanzar(Duration.ofMinutes(61));
        controller.enviar(Map.of("destino", "otro@example.com"));
        verify(diagnostico, times(6)).enviarPrueba("otro@example.com");
    }

    @Test
    @DisplayName("the budget is per actor: another ADMIN is not throttled by the first")
    void topePorActor() {
        ActorResolver otro = mock(ActorResolver.class);
        when(otro.current()).thenReturn("segundo-admin");
        CorreoPruebaController deOtro = new CorreoPruebaController(diagnostico, usuarios, otro, reloj);
        when(diagnostico.enviarPrueba("x@example.com")).thenReturn(EstadoSmtp.ok("enviado"));
        for (int i = 0; i < 5; i++) {
            controller.enviar(Map.of("destino", "x@example.com"));
        }

        assertThat(deOtro.enviar(Map.of("destino", "x@example.com")).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("every send is audited at INFO with the actor and the masked destination, never the full address")
    void auditoria() {
        when(diagnostico.enviarPrueba("victima@example.com")).thenReturn(EstadoSmtp.ok("enviado"));

        controller.enviar(Map.of("destino", "victima@example.com"));

        assertThat(log.list).extracting(ILoggingEvent::getFormattedMessage).singleElement().asString()
                .contains("test-admin", "v**@example.com").doesNotContain("victima@example.com");
    }
}
