package ar.scraper.config;

import ar.scraper.scheduling.CronJobService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CronTicker {

    private final CronJobService service;

    public CronTicker(CronJobService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 30_000)
    public void tick() {
        service.tick();
    }
}
