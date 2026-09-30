package ar.scraper.config;

import ar.scraper.pcs.TechSpecsIndexer;
import ar.scraper.pcs.PcBuilder;
import ar.scraper.pcs.TechSpecsPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PcsConfig {

    @Bean
    PcBuilder pcBuilder() {
        return new PcBuilder();
    }

    @Bean
    TechSpecsIndexer techSpecsIndexer(TechSpecsPort port) {
        return new TechSpecsIndexer(port);
    }
}
