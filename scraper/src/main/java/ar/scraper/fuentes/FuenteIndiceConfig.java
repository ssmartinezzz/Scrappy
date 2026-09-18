package ar.scraper.fuentes;

import ar.scraper.indices.FuenteIndiceEncadenada;
import ar.scraper.indices.FuenteIndicePort;
import ar.scraper.indices.Indice;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/** Builds the source chain per {@link Indice} — argentinadatos first, INDEC as fallback for IPC. */
@Configuration
class FuenteIndiceConfig {

    @Bean
    Map<Indice, FuenteIndicePort> fuentesPorIndice(ArgentinaDatosIpcFuente argentinaDatosIpc,
                                                    DatosGobIpcFuente datosGobIpc,
                                                    ArgentinaDatosDolarFuente dolarOficial) {
        return Map.of(
                Indice.IPC, new FuenteIndiceEncadenada(List.of(argentinaDatosIpc, datosGobIpc)),
                Indice.USD_OFICIAL, new FuenteIndiceEncadenada(List.of(dolarOficial)));
    }
}
