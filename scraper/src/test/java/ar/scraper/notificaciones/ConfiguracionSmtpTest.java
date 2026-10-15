package ar.scraper.notificaciones;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConfiguracionSmtp — spots the settings that can never work, before any connection")
class ConfiguracionSmtpTest {

    private static ConfiguracionSmtp con(String host, int port, String user, String pass, String from) {
        return new ConfiguracionSmtp(host, port, user, pass, from);
    }

    @Test
    @DisplayName("a complete Gmail-style configuration has no problem")
    void completa() {
        assertThat(con("smtp.gmail.com", 587, "cuenta@gmail.com", "clave", "cuenta@gmail.com").problema()).isEmpty();
    }

    @Test
    @DisplayName("an unauthenticated relay (no username, no password) with a from address is fine")
    void relaySinAuth() {
        assertThat(con("localhost", 1025, "", "", "avisos@example.com").problema()).isEmpty();
    }

    @Test
    @DisplayName("a port outside 1..65535 is a problem that names SMTP_PORT")
    void puerto() {
        assertThat(con("smtp.example.com", 0, "", "", "a@example.com").problema()).hasValueSatisfying(
                p -> assertThat(p).contains("SMTP_PORT"));
        assertThat(con("smtp.example.com", 70000, "", "", "a@example.com").problema()).isPresent();
    }

    @Test
    @DisplayName("a host with a scheme, a path or spaces is a problem that names SMTP_HOST")
    void host() {
        for (String malo : new String[]{"smtp://smtp.gmail.com", "smtp.gmail.com:587", "smtp gmail.com", "smtp.gmail.com/"}) {
            assertThat(con(malo, 587, "", "", "a@example.com").problema())
                    .as(malo).hasValueSatisfying(p -> assertThat(p).contains("SMTP_HOST"));
        }
    }

    @Test
    @DisplayName("without SMTP_FROM_ADDRESS the username must itself be an address, or providers reject the sender")
    void remitente() {
        assertThat(con("smtp.example.com", 587, "usuario", "clave", "").problema()).hasValueSatisfying(
                p -> assertThat(p).contains("SMTP_FROM_ADDRESS"));
        assertThat(con("smtp.example.com", 587, "usuario@example.com", "clave", "").problema()).isEmpty();
    }

    @Test
    @DisplayName("a username without a password is a problem that names SMTP_PASSWORD")
    void sinPassword() {
        assertThat(con("smtp.example.com", 587, "usuario@example.com", "", "").problema()).hasValueSatisfying(
                p -> assertThat(p).contains("SMTP_PASSWORD"));
    }

    @Test
    @DisplayName("destino() says host:port and the TLS mode, never the credentials")
    void destino() {
        assertThat(con("smtp.gmail.com", 587, "cuenta@gmail.com", "clave-secreta", "").destino())
                .isEqualTo("smtp.gmail.com:587 (STARTTLS)");
        assertThat(con("smtp.gmail.com", 465, "cuenta@gmail.com", "clave-secreta", "").destino())
                .isEqualTo("smtp.gmail.com:465 (SSL/TLS)");
    }

    @Test
    @DisplayName("destino() does not claim STARTTLS when the operator opted out of requiring it")
    void destinoSinStarttlsObligatorio() {
        assertThat(new ConfiguracionSmtp("relay.local", 25, "", "", "a@example.com", false).destino())
                .isEqualTo("relay.local:25 (sin TLS obligatorio)");
    }
}
