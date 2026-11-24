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
import com.microsoft.playwright.Playwright;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Epic("Scraping")
@Feature("Run")
@DisplayName("ScraperService — per-site refresh and a run that dies")
class ScraperServiceCorridaTest {

    private ScraperConfig config;
    private ResultAggregator aggregator;
    private ProductPort productos;
    private AggregatedResult snapshot;
    private Product enCatalogo;
    private final AtomicInteger sitiosIniciados = new AtomicInteger();

    @BeforeEach
    void setUp() {
        config = mock(ScraperConfig.class);
        aggregator = mock(ResultAggregator.class);
        productos = mock(ProductPort.class);
        enCatalogo = producto("u/previo");
        snapshot = new AggregatedResult(List.of(enCatalogo), Map.of("S", 1), Map.of(),
                ResultAggregator.calcularFacets(List.of(enCatalogo)), 0, 0);
        when(config.getThreadsParalelos()).thenReturn(1);
        when(aggregator.normalizarSolo(anyList())).thenAnswer(i -> i.getArgument(0));
        when(aggregator.fromDB(anyList())).thenReturn(snapshot);
        when(aggregator.fusionarRefrescados(any(), anySet(), anyList())).thenReturn(snapshot);
        when(aggregator.agregar(anyList(), anyBoolean(), any())).thenReturn(snapshot);
        when(aggregator.fromDBParcial(anyList(), any(), anySet())).thenReturn(snapshot);
        when(productos.cargarProductos()).thenReturn(List.of(enCatalogo));
        when(productos.upsertParcial(anyString(), anyList(), any())).thenReturn(UpsertStats.CERO);
    }

    private static Product producto(String url) {
        return Product.builder().sitio("S").nombre("N " + url).precio(10).url(url)
                .imagenUrl("").categoria("Remera").genero("").talles(List.of()).build();
    }

    private void sitios(String... nombres) {
        List<ScraperConfig.SiteConfig> l = java.util.Arrays.stream(nombres)
                .map(n -> new ScraperConfig.SiteConfig(n, "http://" + n, "indumentaria")).toList();
        when(config.getSitiosActivos()).thenReturn(l);
    }

    private ScraperService servicio(java.util.function.Function<ScraperConfig.SiteConfig, ScrapeResult> sitio) {
        return new ScraperService(config, aggregator, mock(ScrapeRunPort.class), mock(SitiosPort.class),
                mock(MlOutputPort.class), mock(SiteRegistry.class), productos, mock(TechSpecsIndexer.class),
                e -> { }, StatusEvents.NONE) {
            @Override
            ScrapeResult scrapearConNavegador(ScraperConfig.SiteConfig site) {
                return sitio.apply(site);
            }
        };
    }

    private static void esperar(ScraperService s, ScraperStatus wanted) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && s.getStatus() != wanted) Thread.sleep(10);
        assertThat(s.getStatus()).isEqualTo(wanted);
    }

    @SuppressWarnings("unchecked")
    private static Set<Playwright> vivos(ScraperService s) throws Exception {
        var f = ScraperService.class.getDeclaredField("playwrightsVivos");
        f.setAccessible(true);
        return (Set<Playwright>) f.get(s);
    }

    @Test
    @DisplayName("with a snapshot, a finished site refreshes by its URLs and the full catalogue loads once, at the end")
    void refrescoPorUrl() throws Exception {
        sitios("A", "B");
        ScraperService s = servicio(site -> new ScrapeResult(site.nombre(),
                List.of(producto("u/" + site.nombre())), null, 1));
        s.cargarDesdeBD();
        Mockito.clearInvocations(productos);

        assertThat(s.iniciarScraping(Set.of())).isTrue();
        esperar(s, ScraperStatus.DONE);

        verify(productos).cargarProductosPorUrl(Set.of("u/A"));
        verify(productos).cargarProductosPorUrl(Set.of("u/B"));
        verify(aggregator, times(2)).fusionarRefrescados(eq(snapshot), anySet(), anyList());
        verify(productos, times(1)).cargarProductos();
    }

    @Test
    @DisplayName("without a snapshot the first site falls back to the full load")
    void sinSnapshot() throws Exception {
        sitios("A");
        ScraperService s = servicio(site -> new ScrapeResult(site.nombre(),
                List.of(producto("u/" + site.nombre())), null, 1));

        assertThat(s.iniciarScraping(Set.of())).isTrue();
        esperar(s, ScraperStatus.DONE);

        verify(productos, never()).cargarProductosPorUrl(anyCollection());
        verify(aggregator).fromDBParcial(anyList(), any(), eq(Set.of("u/A")));
    }

    @Test
    @DisplayName("a failed targeted read leaves the snapshot and the run goes on")
    void lecturaFallida() throws Exception {
        sitios("A");
        when(productos.cargarProductosPorUrl(anyCollection())).thenThrow(new IllegalStateException("db"));
        ScraperService s = servicio(site -> new ScrapeResult(site.nombre(),
                List.of(producto("u/A")), null, 1));
        s.cargarDesdeBD();

        s.iniciarScraping(Set.of());
        esperar(s, ScraperStatus.DONE);

        verify(aggregator, never()).fusionarRefrescados(any(), anySet(), anyList());
    }

    @Test
    @DisplayName("an Error while collecting stops the pool, closes live browsers, and ends in ERROR without cancelling")
    void errorDuranteLaRecoleccion() throws Exception {
        sitios("A", "B", "C");
        CountDownLatch enVuelo = new CountDownLatch(1);
        // The Error waits for the next site to be running: otherwise it may stop the pool first.
        when(productos.upsertParcial(anyString(), anyList(), any())).thenAnswer(i -> {
            assertThat(enVuelo.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            throw new OutOfMemoryError("Java heap space");
        });
        Playwright vivo = mock(Playwright.class);
        CountDownLatch interrumpido = new CountDownLatch(1);
        AtomicInteger orden = new AtomicInteger();
        ScraperService[] ref = new ScraperService[1];
        ref[0] = servicio(site -> {
            sitiosIniciados.incrementAndGet();
            if (orden.getAndIncrement() == 0) {
                return new ScrapeResult(site.nombre(), List.of(producto("u/" + site.nombre())), null, 1);
            }
            try {
                vivos(ref[0]).add(vivo);
                enVuelo.countDown();
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrumpido.countDown();
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new ScrapeResult(site.nombre(), List.of(), "interrumpido", 0);
        });

        ref[0].iniciarScraping(Set.of());
        esperar(ref[0], ScraperStatus.ERROR);

        assertThat(interrumpido.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("the site in flight is interrupted by shutdownNow").isTrue();
        Thread.sleep(300);
        assertThat(sitiosIniciados.get()).as("the queued site never starts").isEqualTo(2);
        verify(vivo).close();
        assertThat(ref[0].estaCancelado()).isFalse();
        assertThat(ref[0].getStatusMsg()).contains("Java heap space");
    }
}
