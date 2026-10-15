package ar.scraper.notificaciones;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DiagnosticoSmtp — connection check, then a real test mail, always without the full address")
class DiagnosticoSmtpTest {

    private static final class MailFalso implements MailPort {
        final List<CorreoDirigido> enviados = new ArrayList<>();
        EstadoSmtp conexion = EstadoSmtp.ok("ok");
        Entrega entrega = Entrega.ok();

        @Override
        public Map<String, Entrega> enviar(List<CorreoDirigido> correos) {
            enviados.addAll(correos);
            return Map.of(correos.get(0).destino(), entrega);
        }

        @Override
        public EstadoSmtp probarConexion() {
            return conexion;
        }
    }

    @Test
    @DisplayName("probar() is whatever the port found")
    void probar() {
        MailFalso mail = new MailFalso();
        mail.conexion = EstadoSmtp.fallo("no", "pista");

        assertThat(new DiagnosticoSmtp(mail).probar()).isEqualTo(mail.conexion);
    }

    @Test
    @DisplayName("a test mail goes to the given address, with a recognizable subject, and reports OK masked")
    void envia() {
        MailFalso mail = new MailFalso();

        EstadoSmtp e = new DiagnosticoSmtp(mail).enviarPrueba("titular@example.com");

        assertThat(e.ok()).isTrue();
        assertThat(e.mensaje()).contains("t**@example.com").doesNotContain("titular@example.com");
        assertThat(mail.enviados).singleElement().satisfies(c -> {
            assertThat(c.destino()).isEqualTo("titular@example.com");
            assertThat(c.correo().asunto()).containsIgnoringCase("prueba");
        });
    }

    @Test
    @DisplayName("when the connection check fails or SMTP is not configured nothing is sent")
    void noEnviaSiLaConexionFalla() {
        MailFalso mail = new MailFalso();
        mail.conexion = EstadoSmtp.noConfigurado();
        assertThat(new DiagnosticoSmtp(mail).enviarPrueba("titular@example.com").nivel())
                .isEqualTo(EstadoSmtp.Nivel.NO_CONFIGURADO);

        mail.conexion = EstadoSmtp.fallo("no", "pista");
        assertThat(new DiagnosticoSmtp(mail).enviarPrueba("titular@example.com").nivel())
                .isEqualTo(EstadoSmtp.Nivel.FALLO);

        assertThat(mail.enviados).isEmpty();
    }

    @Test
    @DisplayName("a server rejection of the message is a FALLO whose text carries no full address")
    void rechazo() {
        MailFalso mail = new MailFalso();
        mail.entrega = Entrega.rechazo("SMTPAddressFailedException: 550 titular@example.com unknown");

        EstadoSmtp e = new DiagnosticoSmtp(mail).enviarPrueba("titular@example.com");

        assertThat(e.nivel()).isEqualTo(EstadoSmtp.Nivel.FALLO);
        assertThat(e.mensaje()).contains("t**@example.com").doesNotContain("titular@example.com");
    }
}
