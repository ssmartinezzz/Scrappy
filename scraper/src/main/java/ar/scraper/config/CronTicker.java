package ar.scraper.config;

import ar.scraper.scheduling.CronJobService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CronTicker {

    private final CronJobService service;

    @Scheduled(fixedDelay = 30_000)
    public void tick() {
        service.tick();
    }
}
