package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.model.ScrapeResult;
import ar.scraper.scrape.ScrapeRunPort;
import ar.scraper.web.ScraperService.SitioEstado;
import ar.scraper.web.ScraperService.SitioProgress;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Epic("Scraping")
@Feature("Scrape run tracking")
@DisplayName("ScraperService — a site is marked running when its task starts, not when it is queued")
class ScraperServiceSitioInicioTest {

    private static final ScraperConfig.SiteConfig SITIO =
            new ScraperConfig.SiteConfig("Sitio", "https://site.com", "indumentaria");

    private ScrapeRunPort scrapeRun;
    private ScraperService service;
    private List<SitioProgress> progreso;

    @BeforeEach
    void setUp() {
        scrapeRun = Mockito.mock(ScrapeRunPort.class);
        when(scrapeRun.crear(any(), any(), any(), any(), any())).thenReturn(7L);
        when(scrapeRun.startedAtDe(7L)).thenReturn(Optional.of(Instant.now()));
        service = new ScraperService(Mockito.mock(ScraperConfig.class),
                Mockito.mock(ResultAggregator.class), scrapeRun,
                Mockito.mock(ar.scraper.classification.SitiosPort.class),
                Mockito.mock(ar.scraper.ml.MlOutputPort.class),
                Mockito.mock(ar.scraper.classification.SiteRegistry.class),
                Mockito.mock(ar.scraper.catalog.ProductPort.class),
                Mockito.mock(ar.scraper.pcs.TechSpecsIndexer.class));
        service.abrirRun(List.of(SITIO));
        progreso = Collections.synchronizedList(new ArrayList<>(
                List.of(new SitioProgress("Sitio", SitioEstado.ESPERANDO, 0, null, 0))));
    }

    @Test
    @DisplayName("a site starting mid-run keeps the run's counters instead of resetting them to zero")
    void lateStartKeepsTheCounters() throws Exception {
        service.progreso(new ScraperService.ProgressData(1, 5, 1234, List.copyOf(progreso)));

        service.correrSitio(SITIO, 0, progreso, 1, () -> {
            var enCurso = service.getProgressData();
            assertThat(enCurso.completados()).isEqualTo(5);
            assertThat(enCurso.productosAcumulados()).isEqualTo(1234);
            assertThat(enCurso.sitios().get(0).estado()).isEqualTo(SitioEstado.EN_CURSO);
            return new ScrapeResult("Sitio", List.of(), null, 0);
        });

        verify(scrapeRun, times(1)).marcarSitioEnCurso(anyLong(), any(), any());
    }

    @Test
    @DisplayName("the mark lands before the scrape body runs")
    void markedBeforeTheScrapeBody() throws Exception {
        List<SitioEstado> alEmpezar = new ArrayList<>();

        service.correrSitio(SITIO, 0, progreso, 1, () -> {
            verify(scrapeRun).marcarSitioEnCurso(eq(7L), eq("Sitio"), any());
            alEmpezar.add(progreso.get(0).estado());
            return new ScrapeResult("Sitio", List.of(), null, 0);
        });

        assertThat(alEmpezar).containsExactly(SitioEstado.EN_CURSO);
    }

    @Test
    @DisplayName("a retried scrape is marked once, not once per attempt")
    void markedOncePerTask() throws Exception {
        AtomicInteger intentos = new AtomicInteger();

        service.correrSitio(SITIO, 0, progreso, 1, () -> {
            if (intentos.incrementAndGet() == 1) throw new IllegalStateException("flaky");
            return new ScrapeResult("Sitio", List.of(), null, 0);
        });

        assertThat(intentos).hasValue(2);
        verify(scrapeRun, times(1)).marcarSitioEnCurso(anyLong(), any(), any());
    }

    @Test
    @DisplayName("submission order follows the historical durations, longest first")
    void ordersByHistory() {
        when(scrapeRun.duracionesHistoricasMs()).thenReturn(java.util.Map.of("corto", 10L, "largo", 99L));

        var orden = service.ordenarPorHistorial(List.of(sitio("Corto"), sitio("Largo")));

        assertThat(orden).extracting(ScraperConfig.SiteConfig::nombre).containsExactly("Largo", "Corto");
    }

    @Test
    @DisplayName("an unreadable history keeps config order instead of failing the run")
    void historyFailureKeepsConfigOrder() {
        when(scrapeRun.duracionesHistoricasMs()).thenThrow(new IllegalStateException("db down"));
        var config = List.of(sitio("Corto"), sitio("Largo"));

        assertThat(service.ordenarPorHistorial(config)).isEqualTo(config);
    }

    private static ScraperConfig.SiteConfig sitio(String nombre) {
        return new ScraperConfig.SiteConfig(nombre, "https://site.com", "indumentaria");
    }
}
