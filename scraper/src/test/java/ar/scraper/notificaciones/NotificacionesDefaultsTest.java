package ar.scraper.notificaciones;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.PropertyPlaceholderHelper;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("notificaciones — shipped defaults")
class NotificacionesDefaultsTest {

    private static String valorPorDefecto(String clave) throws IOException {
        Properties props = new Properties();
        try (var in = NotificacionesDefaultsTest.class.getClassLoader().getResourceAsStream("application.properties")) {
            props.load(in);
        }
        return new PropertyPlaceholderHelper("${", "}", ":", null, true)
                .replacePlaceholders(props.getProperty(clave), nombre -> null);
    }

    @Test
    @DisplayName("the mail cap defaults to 60 a minute: a shared-cluster default of 'no cap' lets one big scrape flood the provider")
    void topePorMinuto() throws IOException {
        assertThat(valorPorDefecto("notificaciones.max-por-minuto")).isEqualTo("60");
    }
}
