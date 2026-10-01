package ar.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.awt.Desktop;
import java.net.URI;
import java.time.Clock;
import org.apache.commons.lang3.StringUtils;

@SpringBootApplication
@EnableScheduling
public class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    /**
     * Reloj del sistema en la zona local del servidor — inyectado en
     * {@code CronJobService}/{@code CronJobRunner} (scraper-cronjobs) para que los tests puedan
     * sustituirlo por un {@link Clock#fixed} sin contexto de Spring.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /** Tomcat ya acepta conexiones, sin dormir a ciegas. */
    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        String url = System.getenv("APP_OPEN_URL");
        if (StringUtils.isBlank(url)) {
            LOG.debug("APP_OPEN_URL no configurada — no se abre el navegador (backend API-only)");
            return;
        }
        try {
            LOG.info("Abriendo navegador en {}", url);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {}
    }
}
