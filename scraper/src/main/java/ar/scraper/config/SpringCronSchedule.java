package ar.scraper.config;

import ar.scraper.scheduling.CronSchedule;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

@Component
public class SpringCronSchedule implements CronSchedule {

    @Override
    public ZonedDateTime nextRun(String cronExpr, ZonedDateTime from) {
        return CronExpression.parse(cronExpr).next(from);
    }

    @Override
    public boolean isValid(String cronExpr) {
        try {
            CronExpression.parse(cronExpr);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
