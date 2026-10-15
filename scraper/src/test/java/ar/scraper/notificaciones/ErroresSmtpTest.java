package ar.scraper.notificaciones;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ErroresSmtp — turns a mail exception into the one thing the operator should do")
class ErroresSmtpTest {

    private static EstadoSmtp de(Throwable t) {
        return ErroresSmtp.clasificar(t, "smtp.example.com", 587);
    }

    @Test
    @DisplayName("rejected credentials point at the app password")
    void auth() {
        EstadoSmtp e = de(new MessagingException("failed", new AuthenticationFailedException("535 Username and Password not accepted")));

        assertThat(e.nivel()).isEqualTo(EstadoSmtp.Nivel.FALLO);
        assertThat(e.mensaje()).containsIgnoringCase("usuario o la contraseña");
        assertThat(e.pista()).containsIgnoringCase("contraseña de aplicación");
    }

    @Test
    @DisplayName("a refused connection names host:port and the 587/465 convention")
    void conexion() {
        EstadoSmtp e = de(new MessagingException("Could not connect", new ConnectException("Connection refused")));

        assertThat(e.mensaje()).contains("smtp.example.com:587");
        assertThat(e.pista()).contains("587").contains("465");
    }

    @Test
    @DisplayName("an unknown host says so and points at SMTP_HOST")
    void hostDesconocido() {
        EstadoSmtp e = de(new MessagingException("x", new UnknownHostException("smtp.example.com")));

        assertThat(e.mensaje()).containsIgnoringCase("no se encontró");
        assertThat(e.pista()).contains("SMTP_HOST");
    }

    @Test
    @DisplayName("a timeout is reported as a timeout, with the firewall/port hint")
    void timeout() {
        EstadoSmtp e = de(new MessagingException("x", new SocketTimeoutException("Read timed out")));

        assertThat(e.mensaje()).containsIgnoringCase("tiempo");
        assertThat(e.pista()).contains("puerto");
    }

    @Test
    @DisplayName("a TLS handshake failure points at the port/TLS mode")
    void tls() {
        EstadoSmtp e = de(new MessagingException("x", new SSLHandshakeException("PKIX path building failed")));

        assertThat(e.mensaje()).contains("TLS");
        assertThat(e.pista()).contains("465");
    }

    @Test
    @DisplayName("an unknown failure keeps its class and message but loses any address in it")
    void generico() {
        EstadoSmtp e = de(new MessagingException("550 5.1.1 titular@example.com blocked"));

        assertThat(e.mensaje()).contains("MessagingException").contains("t**@example.com").doesNotContain("titular@example.com");
    }

    @Test
    @DisplayName("sinDirecciones masks every address in a text")
    void sinDirecciones() {
        assertThat(ErroresSmtp.sinDirecciones("de a.b@x.org para titular@example.com fin"))
                .isEqualTo("de a**@x.org para t**@example.com fin");
    }

    @Test
    @DisplayName("sinDirecciones also masks quoted, non-ASCII and bracketed addresses")
    void sinDireccionesAmplio() {
        assertThat(ErroresSmtp.sinDirecciones("a \"juan perez\"@x.org b ñandú@example.com c <z@y.net> d"))
                .isEqualTo("a \"**@x.org b ñ**@example.com c <z**@y.net> d")
                .doesNotContain("juan", "andú", "z@y");
    }

    @Test
    @DisplayName("sinDirecciones is linear: a large text with no address, or with thousands of '@', is cheap")
    void sinDireccionesLineal() {
        long inicio = System.nanoTime();
        ErroresSmtp.sinDirecciones("a".repeat(200_000));
        ErroresSmtp.sinDirecciones("a@".repeat(100_000));
        ErroresSmtp.sinDirecciones("@".repeat(200_000));

        assertThat((System.nanoTime() - inicio) / 1_000_000).as("ms").isLessThan(1000);
    }
}
