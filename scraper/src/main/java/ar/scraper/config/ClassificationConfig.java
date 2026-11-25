package ar.scraper.config;

import ar.scraper.classification.BrandExtractor;
import ar.scraper.classification.RubroCatalogo;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.RubroSource;
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
    RubroCatalogo rubroCatalogo(RubroSource rubroSource) {
        return rubroSource.cargar();
    }

    @Bean
    RubroResolver rubroResolver(SiteRegistry siteRegistry, RubroCatalogo rubroCatalogo) {
        return new RubroResolver(siteRegistry, rubroCatalogo);
    }

    @Bean
    BrandExtractor brandExtractor() {
        return new BrandExtractor();
    }
}
