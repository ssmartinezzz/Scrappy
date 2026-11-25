package ar.scraper.config;

import ar.scraper.classification.RubroCatalogo;
import ar.scraper.indices.DeflactorPorRubro;
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
    DeflactorPorRubro deflactorPorRubro(RubroCatalogo rubros) {
        return new DeflactorPorRubro(rubros::indiceDeCategoria, rubros::indiceDeRubro);
    }

    @Bean
    IndiceService indiceService(IndicePort indicePort, Map<Indice, FuenteIndicePort> fuentesPorIndice,
                                DeflactorPorRubro deflactorPorRubro) {
        return new IndiceService(indicePort, fuentesPorIndice, deflactorPorRubro);
    }

    @Bean
    IndiceRefreshJob indiceRefreshJob(IndiceService indiceService) {
        return new IndiceRefreshJob(indiceService);
    }
}
