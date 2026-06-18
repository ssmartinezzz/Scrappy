package ar.scraper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import java.awt.Desktop;
import java.net.URI;

@SpringBootApplication
@EnableScheduling
public class App {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
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
