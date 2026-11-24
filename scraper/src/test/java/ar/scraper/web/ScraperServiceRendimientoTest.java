package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.ProductPort;
import ar.scraper.catalog.UpsertStats;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.classification.SitiosPort;
import ar.scraper.config.ScraperConfig;
import ar.scraper.ml.MlOutputPort;
import ar.scraper.model.Product;
import ar.scraper.model.ScrapeResult;
import ar.scraper.pcs.TechSpecsIndexer;
import ar.scraper.scrape.ScrapeRunPort;
import ar.scraper.scrape.ScraperStatus;
import ar.scraper.scrape.StatusEvents;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Epic("Scraping Engine")
@Feature("Run health")
@Story("Site yield collapse")
@DisplayName("ScraperService — the yield guard compares this run against the last positive one")
class ScraperServiceRendimientoTest {

    private ScraperConfig config;
    private ResultAggregator aggregator;
    private ProductPort productos;
    private ScrapeRunPort scrapeRun;
    private AggregatedResult snapshot;

    @BeforeEach
    void setUp() {
        config = mock(ScraperConfig.class);
        aggregator = mock(ResultAggregator.class);
        productos = mock(ProductPort.class);
        scrapeRun = mock(ScrapeRunPort.class);
        Product enCatalogo = Product.builder().sitio("Venex").nombre("N").precio(10).url("u/previo")
                .imagenUrl("").categoria("Remera").genero("").talles(List.of()).build();
        // The catalogue still holds the site's rows: the sweep never retires a site that came back empty.
        snapshot = new AggregatedResult(List.of(enCatalogo), Map.of("Venex", 2779), Map.of(),
                ResultAggregator.calcularFacets(List.of(enCatalogo)), 0, 0);
        when(config.getThreadsParalelos()).thenReturn(1);
        when(aggregator.normalizarSolo(anyList())).thenAnswer(i -> i.getArgument(0));
        when(aggregator.fromDB(anyList())).thenReturn(snapshot);
        when(aggregator.fusionarRefrescados(any(), anySet(), anyList())).thenReturn(snapshot);
        when(aggregator.agregar(anyList(), anyBoolean(), any())).thenReturn(snapshot);
        when(aggregator.fromDBParcial(anyList(), any(), anySet())).thenReturn(snapshot);
        when(productos.cargarProductos()).thenReturn(snapshot.productos());
        when(productos.upsertParcial(anyString(), anyList(), any())).thenReturn(UpsertStats.CERO);
    }

    private ScraperService servicio(java.util.function.Function<ScraperConfig.SiteConfig, ScrapeResult> sitio) {
        return new ScraperService(config, aggregator, scrapeRun, mock(SitiosPort.class),
                mock(MlOutputPort.class), mock(SiteRegistry.class), productos, mock(TechSpecsIndexer.class),
                e -> { }, StatusEvents.NONE) {
            @Override
            ScrapeResult scrapearConNavegador(ScraperConfig.SiteConfig site) {
                return sitio.apply(site);
            }
        };
    }

    private static void esperar(ScraperService s) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && s.getStatus() != ScraperStatus.DONE) Thread.sleep(10);
        assertThat(s.getStatus()).isEqualTo(ScraperStatus.DONE);
    }

    private static ScrapeResult vacio(String sitio) {
        return new ScrapeResult(sitio, List.of(), null, 1);
    }

    @Test
    @DisplayName("a zero-yield site raises the alert while the catalogue keeps its rows")
    void alertWhileCatalogKeepsRows() throws Exception {
        when(config.getSitiosActivos()).thenReturn(
                List.of(new ScraperConfig.SiteConfig("Venex", "http://venex", "tecnologia")));
        when(scrapeRun.ultimoRendimientoPositivo(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Map.of("venex", 2779));
        ScraperService s = servicio(site -> vacio(site.nombre()));

        s.iniciarScraping(Set.of());
        esperar(s);

        assertThat(s.getLastResult().conteoPorSitio()).containsEntry("Venex", 2779);
        assertThat(s.getLastResult().erroresPorSitio()).containsKey("Venex");
        assertThat(s.getLastResult().erroresPorSitio().get("Venex")).contains("2779");
    }

    @Test
    @DisplayName("a failed history read skips the guard and the run still finishes")
    void historyReadFailureDoesNotKillTheRun() throws Exception {
        when(config.getSitiosActivos()).thenReturn(
                List.of(new ScraperConfig.SiteConfig("Venex", "http://venex", "tecnologia")));
        when(scrapeRun.ultimoRendimientoPositivo(org.mockito.ArgumentMatchers.anyLong()))
                .thenThrow(new IllegalStateException("db down"));
        ScraperService s = servicio(site -> vacio(site.nombre()));

        s.iniciarScraping(Set.of());
        esperar(s);

        assertThat(s.getLastResult().erroresPorSitio()).isEmpty();
    }

    @Test
    @DisplayName("replay of run 41: seven sites, all empty, seven alerts; names map to sitio_key")
    void replayOfRun41() {
        Map<String, Integer> baseline = Map.of("venex", 2779, "fursten", 48, "vcp", 908, "freres", 134,
                "flowin", 10, "baesics", 66, "forever", 6);
        when(scrapeRun.ultimoRendimientoPositivo(org.mockito.ArgumentMatchers.anyLong())).thenReturn(baseline);
        ScraperService s = servicio(site -> vacio(site.nombre()));
        List<ScrapeResult> resultados = new ArrayList<>();
        for (String nombre : List.of("Venex", "Fursten", "VCP", "Freres", "Flowin", "Baesics", "Forever")) {
            resultados.add(vacio(nombre));
        }

        assertThat(s.alertasDeRendimiento(resultados, null)).hasSize(7);
    }

    @Test
    @DisplayName("a resumed run only judges the sites it holds")
    void resumedRunJudgesOnlyItsOwnSites() {
        when(scrapeRun.ultimoRendimientoPositivo(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Map.of("venex", 2779, "vcp", 908));
        ScraperService s = servicio(site -> vacio(site.nombre()));

        assertThat(s.alertasDeRendimiento(List.of(vacio("Venex")), null))
                .extracting(a -> a.sitio()).containsExactly("Venex");
    }
}
