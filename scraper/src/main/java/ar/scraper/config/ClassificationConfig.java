package ar.scraper.config;

import ar.scraper.classification.BrandExtractor;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.classification.SiteSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ClassificationConfig {

    @Bean
    SiteRegistry siteRegistry(SiteSource siteSource) {
        return new SiteRegistry(siteSource);
    }

    @Bean
    RubroResolver rubroResolver(SiteRegistry siteRegistry) {
        return new RubroResolver(siteRegistry);
    }

    @Bean
    BrandExtractor brandExtractor() {
        return new BrandExtractor();
    }
}
