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

    /**
     * Abre el browser automaticamente cuando Spring arranca — gated detrás de
     * {@code APP_OPEN_URL} (decouple-services-postgres, Batch 3, design D6).
     * El backend ahora es API-only (sin SPA embebida en {@code static/}), así
     * que ya no hay una página propia útil para abrir por default: sin la
     * variable de entorno, este listener es un no-op. Si se define
     * {@code APP_OPEN_URL} (por ejemplo, apuntando al frontend standalone),
     * se abre esa URL.
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onStart() {
        String url = System.getenv("APP_OPEN_URL");
        if (url == null || url.isBlank()) {
            LOG.debug("APP_OPEN_URL no configurada — no se abre el navegador (backend API-only)");
            return;
        }
        try {
            Thread.sleep(500); // esperar que Tomcat este listo
            LOG.info("Abriendo navegador en {}", url);
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception ignored) {}
    }
}
