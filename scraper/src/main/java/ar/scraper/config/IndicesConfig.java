package ar.scraper.config;

import ar.scraper.indices.FuenteIndicePort;
import ar.scraper.indices.Indice;
import ar.scraper.indices.IndicePort;
import ar.scraper.indices.IndiceRefreshJob;
import ar.scraper.indices.IndiceService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
class IndicesConfig {

    @Bean
    IndiceService indiceService(IndicePort indicePort, Map<Indice, FuenteIndicePort> fuentesPorIndice) {
        return new IndiceService(indicePort, fuentesPorIndice);
    }

    @Bean
    IndiceRefreshJob indiceRefreshJob(IndiceService indiceService) {
        return new IndiceRefreshJob(indiceService);
    }
}
