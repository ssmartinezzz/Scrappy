package ar.scraper.notificaciones;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChequeoSmtpAlArrancar — one actionable line at boot, never a failure")
class ChequeoSmtpAlArrancarTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ChequeoSmtpAlArrancar.class);
    private final ListAppender<ILoggingEvent> log = new ListAppender<>();
    private final AtomicInteger sondeos = new AtomicInteger();
    private EstadoSmtp resultado = EstadoSmtp.ok("conexión aceptada");

    @BeforeEach
    void setUp() {
        log.start();
        logger.addAppender(log);
    }

    @AfterEach
    void limpiar() {
        logger.detachAppender(log);
    }

    private ChequeoSmtpAlArrancar chequeo(ConfiguracionSmtp cfg) {
        MailPort mail = new MailPort() {
            @Override
            public Map<String, Entrega> enviar(List<CorreoDirigido> correos) {
                return Map.of();
            }

            @Override
            public EstadoSmtp probarConexion() {
                sondeos.incrementAndGet();
                if (resultado == null) {
                    throw new IllegalStateException("explotó con secreto-123");
                }
                return resultado;
            }
        };
        return new ChequeoSmtpAlArrancar(new DiagnosticoSmtp(mail), cfg, Runnable::run);
    }

    private static ConfiguracionSmtp valida() {
        return new ConfiguracionSmtp("smtp.example.com", 587, "cuenta@example.com", "secreto-123", "cuenta@example.com");
    }

    private List<String> lineas() {
        return log.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("with SMTP_HOST blank it says nothing and opens no connection")
    void sinHost() {
        chequeo(new ConfiguracionSmtp("", 587, "", "", "")).alArrancar();

        assertThat(log.list).isEmpty();
        assertThat(sondeos).hasValue(0);
    }

    @Test
    @DisplayName("a reachable server logs exactly one INFO line with host:port, no credentials")
    void ok() {
        chequeo(valida()).alArrancar();

        assertThat(log.list).singleElement().satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.INFO));
        assertThat(lineas().get(0)).contains("[SMTP]", "smtp.example.com:587 (STARTTLS)")
                .doesNotContain("secreto-123").doesNotContain("cuenta@example.com");
    }

    @Test
    @DisplayName("a failing server logs exactly one WARN line with the reason and the hint")
    void fallo() {
        resultado = EstadoSmtp.fallo("El servidor rechazó el usuario o la contraseña.", "Usá una contraseña de aplicación.");

        chequeo(valida()).alArrancar();

        assertThat(log.list).singleElement().satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
        assertThat(lineas().get(0)).contains("rechazó el usuario", "contraseña de aplicación");
    }

    @Test
    @DisplayName("settings that cannot work log one WARN naming the key and skip the connection")
    void configInvalida() {
        chequeo(new ConfiguracionSmtp("smtp.example.com", 0, "", "", "a@example.com")).alArrancar();

        assertThat(log.list).singleElement().satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
        assertThat(lineas().get(0)).contains("SMTP_PORT");
        assertThat(sondeos).hasValue(0);
    }

    @Test
    @DisplayName("an exception while probing is logged as one WARN, without its message, and never propagates")
    void explota() {
        resultado = null;

        chequeo(valida()).alArrancar();

        assertThat(log.list).singleElement().satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
        assertThat(lineas().get(0)).doesNotContain("secreto-123");
    }

    @Test
    @DisplayName("the probe runs on the executor it is given, so boot does not wait for it")
    void asincrono() {
        List<Runnable> tareas = new java.util.ArrayList<>();
        ChequeoSmtpAlArrancar c = new ChequeoSmtpAlArrancar(new DiagnosticoSmtp(new MailPort() {
            @Override
            public Map<String, Entrega> enviar(List<CorreoDirigido> correos) {
                return Map.of();
            }

            @Override
            public EstadoSmtp probarConexion() {
                sondeos.incrementAndGet();
                return EstadoSmtp.ok("ok");
            }
        }), valida(), tareas::add);

        c.alArrancar();

        assertThat(sondeos).hasValue(0);
        tareas.forEach(Runnable::run);
        assertThat(sondeos).hasValue(1);
    }
}
