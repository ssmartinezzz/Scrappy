package ar.scraper.notificaciones;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConsoleMailAdapter — logs the mail instead of sending it, without the full address")
class ConsoleMailAdapterTest {

    @Test
    void logueaElAsuntoYEnmascaraLaDireccion() {
        Logger logger = (Logger) LoggerFactory.getLogger(ConsoleMailAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Map<String, Entrega> resultado = new ConsoleMailAdapter().enviar(List.of(new CorreoDirigido(
                    "usuario@example.com", new CorreoRenderizado("Bajó de precio: 1 favorito", "<html/>", "texto"))));

            assertThat(resultado.get("usuario@example.com").enviada()).as("the outbox must drain").isTrue();
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .singleElement().asString()
                    .contains("Bajó de precio: 1 favorito", "u**@example.com")
                    .doesNotContain("usuario@example.com");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
