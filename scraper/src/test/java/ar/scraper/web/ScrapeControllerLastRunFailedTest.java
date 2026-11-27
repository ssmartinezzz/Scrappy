package ar.scraper.web;

import ar.scraper.config.ScraperConfig;
import ar.scraper.scrape.UltimaCorrida;
import ar.scraper.scrape.UltimaCorrida.SitioFallido;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GET /api/scrape/last-run/failed")
class ScrapeControllerLastRunFailedTest {

    private final ScraperService service = mock(ScraperService.class);
    private final ScrapeController controller =
            new ScrapeController(service, mock(ScraperConfig.class), new ScrapeStatusView(service));

    @Test
    void sinCorridaCompletadaNoHayNada() {
        when(service.getUltimaCorridaCompletada()).thenReturn(Optional.empty());

        var data = controller.ultimaCorridaFallida().getBody().getData();

        assertThat(data.isHayCorrida()).isFalse();
        assertThat(data.getFallidos()).isNull();
    }

    @Test
    void devuelveLosFallidosConSuMarcaDeDeadline() {
        UUID uuid = UUID.randomUUID();
        when(service.getUltimaCorridaCompletada()).thenReturn(Optional.of(new UltimaCorrida(
                uuid, Instant.parse("2026-11-27T19:50:00Z"), List.of(
                        new SitioFallido("city", "Deadline global", true),
                        new SitioFallido("freres", "timeout", false)))));

        var data = controller.ultimaCorridaFallida().getBody().getData();

        assertThat(data.isHayCorrida()).isTrue();
        assertThat(data.getUuid()).isEqualTo(uuid.toString());
        assertThat(data.getFinishedAt()).isEqualTo("2026-11-27T19:50:00Z");
        assertThat(data.getFallidos()).extracting("sitio", "porDeadline")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("city", true),
                                 org.assertj.core.groups.Tuple.tuple("freres", false));
    }

    @Test
    void unaCorridaLimpiaDevuelveListaVacia() {
        when(service.getUltimaCorridaCompletada()).thenReturn(Optional.of(
                new UltimaCorrida(UUID.randomUUID(), Instant.now(), List.of())));

        var data = controller.ultimaCorridaFallida().getBody().getData();

        assertThat(data.isHayCorrida()).isTrue();
        assertThat(data.getFallidos()).isEmpty();
    }
}
