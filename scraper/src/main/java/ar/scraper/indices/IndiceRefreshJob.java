package ar.scraper.indices;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// ApplicationRunner, not @PostConstruct: DatabaseService owns its own pool, so it is
// the only hook Spring guarantees to run after Flyway has applied V33.
@Component
public class IndiceRefreshJob implements ApplicationRunner {

    private final IndiceService service;

    public IndiceRefreshJob(IndiceService service) {
        this.service = service;
    }

    @Override
    public void run(ApplicationArguments args) {
        service.cargarDesdeDB();
        Thread.ofVirtual().start(service::refrescar);
    }

    @Scheduled(cron = "0 0 8 * * *")
    public void diario() {
        Thread.ofVirtual().start(service::refrescar);
    }
}
