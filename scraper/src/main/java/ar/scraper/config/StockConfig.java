package ar.scraper.config;

import ar.scraper.catalog.PoliticaStock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration
class StockConfig {

    /** A stock check older than this reads as unknown; see {@link PoliticaStock}. */
    @Bean
    PoliticaStock politicaStock(Clock clock, @Value("${stock.vigencia-dias:7}") long vigenciaDias) {
        if (vigenciaDias < 1) {
            throw new IllegalStateException("STOCK_VIGENCIA_DIAS must be >= 1, got " + vigenciaDias);
        }
        return new PoliticaStock(clock, Duration.ofDays(vigenciaDias));
    }
}
