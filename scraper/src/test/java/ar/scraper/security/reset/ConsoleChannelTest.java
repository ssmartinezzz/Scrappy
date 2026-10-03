package ar.scraper.security.reset;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The console channel is the default, so its default log output must not leak the reset token:
 * anyone who can read {@code logs/} would otherwise take over any account that requests a reset.
 * The clickable link stays available for development, but only when the operator opts into DEBUG.
 */
@Epic("Security")
@Feature("Password reset")
@Story("ConsoleChannel does not leak the token at the default log level")
@DisplayName("ConsoleChannel")
class ConsoleChannelTest {

    private static final String DESTINO = "persona@example.com";
    private static final String TOKEN = "T0kenSecret0DeReseteo";
    private static final String ENLACE = "http://localhost:5173/reset-password#token=" + TOKEN;

    private final Logger logger = (Logger) LoggerFactory.getLogger(ConsoleChannel.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level nivelAntes;

    @BeforeEach
    void attach() {
        nivelAntes = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        logger.setLevel(nivelAntes);
    }

    private String salida() {
        return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    @DisplayName("at the default INFO level it reports the reset but never prints the token")
    void infoNeverPrintsTheToken() {
        logger.setLevel(Level.INFO);

        new ConsoleChannel().enviar(DESTINO, ENLACE);

        String salida = salida();
        assertThat(salida)
                .as("the recipient is useful operationally and is not a secret")
                .contains(DESTINO);
        assertThat(salida)
                .as("the token is the whole credential; the default log must not carry it")
                .doesNotContain(TOKEN)
                .doesNotContain(ENLACE);
    }

    @Test
    @DisplayName("at DEBUG it prints the full link, as a deliberate development opt-in")
    void debugPrintsTheLinkForDevelopment() {
        logger.setLevel(Level.DEBUG);

        new ConsoleChannel().enviar(DESTINO, ENLACE);

        assertThat(salida())
                .as("a developer who raised the level on purpose still gets a clickable link")
                .contains(ENLACE);
    }
}
