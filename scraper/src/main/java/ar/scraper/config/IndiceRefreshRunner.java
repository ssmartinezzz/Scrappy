package ar.scraper.config;

import ar.scraper.indices.IndiceRefreshJob;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// ApplicationRunner, not @PostConstruct:
@Component
public class IndiceRefreshRunner implements ApplicationRunner {

    private final IndiceRefreshJob job;

    public IndiceRefreshRunner(IndiceRefreshJob job) {
        this.job = job;
    }

    @Override
    public void run(ApplicationArguments args) {
        job.alArrancar();
    }

    @Scheduled(cron = "0 0 8 * * *")
    public void diario() {
        job.diario();
    }
}
