package ar.scraper.config;

import ar.scraper.scheduling.CronJobRunner;
import ar.scraper.scheduling.CronJobService;
import ar.scraper.scheduling.CronPort;
import ar.scraper.scheduling.CronSchedule;
import ar.scraper.scheduling.RunLogCapture;
import ar.scraper.scrape.ScrapeControlPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class SchedulingConfig {

    @Bean
    RunLogCapture runLogCapture() {
        return new LogbackRunLogCapture();
    }

    @Bean
    CronJobRunner cronJobRunner(ScrapeControlPort scrape, CronPort db, Clock clock, RunLogCapture logCapture) {
        return new CronJobRunner(scrape, db, clock, logCapture);
    }

    @Bean
    CronJobService cronJobService(CronPort db, CronJobRunner runner, Clock clock, CronSchedule schedule) {
        return new CronJobService(db, runner, clock, schedule);
    }
}
