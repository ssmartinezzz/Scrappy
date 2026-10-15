package ar.scraper.notificaciones;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class NotificacionesConfig {

    @Bean
    RelayConfig relayConfig(@Value("${notificaciones.lote:200}") int lote,
                            @Value("${notificaciones.max-intentos:5}") int maxIntentos,
                            @Value("${notificaciones.lease-segundos:300}") long leaseSegundos,
                            @Value("${notificaciones.backoff-base-segundos:60}") long backoffBaseSegundos,
                            @Value("${notificaciones.max-por-minuto:60}") int maxPorMinuto,
                            @Value("${notificaciones.retencion-dias:30}") long retencionDias,
                            @Value("${notificaciones.purga-cada-horas:6}") long purgaCadaHoras) {
        return new RelayConfig(lote, maxIntentos, Duration.ofSeconds(leaseSegundos),
                Duration.ofSeconds(backoffBaseSegundos), maxPorMinuto,
                Duration.ofDays(retencionDias), Duration.ofHours(purgaCadaHoras));
    }
}
