package ar.scraper.notificaciones;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MailConfig — SMTP when SMTP_HOST is set, console otherwise")
class MailConfigTest {

    private final MailConfig config = new MailConfig();

    @Test
    void conHostUsaSmtp() {
        assertThat(config.mailPort("smtp.example.com", 587, "", "", "avisos@example.com", true))
                .isInstanceOf(SmtpMailAdapter.class);
    }

    @Test
    void sinHostOConEspaciosUsaConsola() {
        assertThat(config.mailPort("", 587, "", "", "", true)).isInstanceOf(ConsoleMailAdapter.class);
        assertThat(config.mailPort("   ", 587, "", "", "", true)).isInstanceOf(ConsoleMailAdapter.class);
    }
}
