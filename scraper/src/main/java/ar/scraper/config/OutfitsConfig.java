package ar.scraper.config;

import ar.scraper.outfits.OutfitService;
import ar.scraper.outfits.RecommendationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OutfitsConfig {

    @Bean
    RecommendationService recommendationService() {
        return new RecommendationService();
    }

    @Bean
    OutfitService outfitService(RecommendationService recommendationService) {
        return new OutfitService(recommendationService);
    }
}
