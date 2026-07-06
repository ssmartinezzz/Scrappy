package ar.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import java.awt.Desktop;
import java.net.URI;
import java.time.Clock;

@SpringBootApplication
@EnableScheduling
public class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    /**
     * Reloj del sistema en la zona local del servidor — inyectado en
     * {@code CronJobService}/{@code CronJobRunner} (scraper-cronjobs) para que
     * los tests puedan sustituirlo por un {@link Clock#fixed} sin contexto de
     * Spring.
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    /** Abrir el browser automaticamente cuando Spring arranca */
    @EventListener(ContextRefreshedEvent.class)
    public void onStart() {
        try {
            Thread.sleep(500); // esperar que Tomcat este listo
            String url = "http://localhost:3000";
            LOG.info("Abriendo navegador en {}", url);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {}
    }
}
