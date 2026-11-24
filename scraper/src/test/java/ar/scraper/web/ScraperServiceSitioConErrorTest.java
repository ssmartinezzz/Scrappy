package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Epic("Scraping")
@Feature("Scrape run tracking")
@DisplayName("ScraperService — a site with an error and no products is persisted as ERROR")
class ScraperServiceSitioConErrorTest {

    private static final ScraperConfig.SiteConfig SITIO =
            new ScraperConfig.SiteConfig("Sitio", "https://site.com", "indumentaria");

    private ScrapeRunPort scrapeRun;
    private ScraperService service;
    private List<SitioProgress> progreso;
    private IndiceDeSitios indice;

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
        indice = new IndiceDeSitios(List.of("Sitio"));
    }

    @Test
    @DisplayName("zero products and an HTTP error: ERROR in the run row and in the progress, with that error")
    void zeroProductsWithErrorIsPersistedAsError() {
        service.cerrarSitio(new ScrapeResult("Sitio", List.of(), "catálogo cortado en inicio: HTTP 403", 120),
                progreso, indice);

        verify(scrapeRun).marcarSitioTerminado(eq(7L), eq("Sitio"), eq("ERROR"), eq(0),
                eq("catálogo cortado en inicio: HTTP 403"), any());
        assertThat(progreso.get(0).estado()).isEqualTo(SitioEstado.ERROR);
        assertThat(progreso.get(0).error()).contains("HTTP 403");
    }

    @Test
    @DisplayName("zero products and no error is a real empty catalog: DONE")
    void zeroProductsWithoutErrorIsDone() {
        service.cerrarSitio(new ScrapeResult("Sitio", List.of(), null, 120), progreso, indice);

        verify(scrapeRun).marcarSitioTerminado(eq(7L), eq("Sitio"), eq("DONE"), eq(0), eq(null), any());
    }

    @Test
    @DisplayName("an error with products is a partial catalog: DONE, the error is kept")
    void partialCatalogIsDoneWithItsError() {
        Product p = Mockito.mock(Product.class);

        service.cerrarSitio(new ScrapeResult("Sitio", List.of(p), "paginación cortada en p3: HTTP 403", 120),
                progreso, indice);

        verify(scrapeRun).marcarSitioTerminado(eq(7L), eq("Sitio"), eq("DONE"), eq(1),
                eq("paginación cortada en p3: HTTP 403"), any());
    }
}
