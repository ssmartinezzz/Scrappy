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
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Epic("Scraping Engine")
@Feature("Run closing")
@Story("The closing releases what it no longer needs without changing what it reports")
@DisplayName("ScraperService — the closing of a run after the aggregation")
class ScraperServiceCierreTest {

    private ScraperConfig config;
    private ResultAggregator aggregator;
    private ProductPort productos;
    private ScrapeRunPort scrapeRun;
    private Logger runLog;
    private ListAppender<ILoggingEvent> lineas;

    private static Product producto(String sitio, String url) {
        return Product.builder().sitio(sitio).nombre("n-" + url).precio(10).url(url).imagenUrl("")
                .categoria("Remera").genero("").talles(List.of()).build();
    }

    @BeforeEach
    void setUp() {
        config = mock(ScraperConfig.class);
        aggregator = mock(ResultAggregator.class);
        productos = mock(ProductPort.class);
        scrapeRun = mock(ScrapeRunPort.class);
        when(config.getThreadsParalelos()).thenReturn(1);
        when(config.getSitiosActivos()).thenReturn(List.of(
                new ScraperConfig.SiteConfig("Lleno", "http://lleno", "indumentaria"),
                new ScraperConfig.SiteConfig("Vacio", "http://vacio", "indumentaria"),
                new ScraperConfig.SiteConfig("Roto", "http://roto", "indumentaria")));
        when(aggregator.normalizarSolo(anyList())).thenAnswer(i -> i.getArgument(0));
        when(productos.upsertParcial(anyString(), anyList(), any())).thenReturn(UpsertStats.CERO);
        when(scrapeRun.ultimoRendimientoPositivo(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Map.of("vacio", 40));
        runLog = (Logger) LoggerFactory.getLogger("ar.scraper.run");
        lineas = new ListAppender<>();
        lineas.start();
        runLog.addAppender(lineas);
    }

    @AfterEach
    void tearDown() {
        runLog.detachAppender(lineas);
    }

    private ScraperService servicio() {
        return new ScraperService(config, aggregator, scrapeRun, mock(SitiosPort.class),
                mock(MlOutputPort.class), mock(SiteRegistry.class), productos, mock(TechSpecsIndexer.class),
                e -> { }, ar.scraper.scrape.StatusEvents.NONE) {
            @Override
            ScrapeResult scrapearConNavegador(ScraperConfig.SiteConfig site) {
                return switch (site.nombre()) {
                    case "Lleno" -> new ScrapeResult("Lleno",
                            List.of(producto("Lleno", "u/1"), producto("Lleno", "u/2")), null, 1);
                    case "Vacio" -> new ScrapeResult("Vacio", List.of(), null, 1);
                    default -> new ScrapeResult("Roto", List.of(), "x".repeat(80), 1);
                };
            }
        };
    }

    private static void esperar(ScraperService s) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && s.getStatus() != ar.scraper.scrape.ScraperStatus.DONE) Thread.sleep(10);
        assertThat(s.getStatus()).isEqualTo(ar.scraper.scrape.ScraperStatus.DONE);
    }

    private AggregatedResult foto(List<Product> lista, Map<String, String> errores) {
        return new AggregatedResult(lista, Map.of("Lleno", lista.size()), errores,
                ResultAggregator.calcularFacets(lista), 0, 0, Map.of());
    }

    private List<String> mensajes() {
        return lineas.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("the aggregation gets the raw results; the lines after it still name the empty and the failed sites")
    void closingLinesSurviveReleasingTheRawResults() throws Exception {
        List<Product> catalogo = List.of(producto("Lleno", "u/1"), producto("Lleno", "u/2"));
        AggregatedResult delBatch = foto(catalogo, Map.of("Roto", "x"));
        when(aggregator.agregar(anyList(), anyBoolean(), any())).thenReturn(delBatch);
        when(productos.cargarProductos()).thenReturn(catalogo);
        when(aggregator.fromDBParcial(anyList(), any(), anySet())).thenReturn(foto(catalogo, Map.of()));
        ScraperService s = servicio();

        s.iniciarScraping(Set.of());
        esperar(s);

        @SuppressWarnings("unchecked") ArgumentCaptor<List<ScrapeResult>> recibidos =
                ArgumentCaptor.forClass(List.class);
        verify(aggregator).agregar(recibidos.capture(), anyBoolean(), any());
        assertThat(recibidos.getValue()).extracting(ScrapeResult::sitio)
                .containsExactlyInAnyOrder("Lleno", "Vacio", "Roto");
        assertThat(recibidos.getValue()).filteredOn(r -> r.sitio().equals("Lleno"))
                .singleElement().satisfies(r -> assertThat(r.productos()).hasSize(2));
        assertThat(mensajes()).anySatisfy(m -> assertThat(m).startsWith("[AVISO]").endsWith("Vacio"));
        assertThat(mensajes()).anySatisfy(m -> assertThat(m)
                .startsWith("[ERRORES]").contains("Roto (" + "x".repeat(50) + "...)"));
        assertThat(mensajes()).anySatisfy(m -> assertThat(m).contains("[SALUD]").contains("Vacio"));
        assertThat(s.getLastResult().erroresPorSitio()).containsKeys("Roto", "Vacio");
    }

    @Test
    @DisplayName("an empty database serves the batch itself, as before")
    void emptyReloadFallsBackToTheBatch() throws Exception {
        AggregatedResult delBatch = foto(List.of(producto("Lleno", "u/1")), Map.of());
        when(aggregator.agregar(anyList(), anyBoolean(), any())).thenReturn(delBatch);
        when(productos.cargarProductos()).thenReturn(List.of());
        ScraperService s = servicio();

        s.iniciarScraping(Set.of());
        esperar(s);

        assertThat(s.getLastResult().productos()).containsExactlyElementsOf(delBatch.productos());
    }

    @Test
    @DisplayName("a reload that fails serves the batch itself, as before")
    void failedReloadFallsBackToTheBatch() throws Exception {
        AggregatedResult delBatch = foto(List.of(producto("Lleno", "u/1")), Map.of());
        when(aggregator.agregar(anyList(), anyBoolean(), any())).thenReturn(delBatch);
        when(productos.cargarProductos()).thenThrow(new IllegalStateException("db down"));
        ScraperService s = servicio();

        s.iniciarScraping(Set.of());
        esperar(s);

        assertThat(s.getLastResult().productos()).containsExactlyElementsOf(delBatch.productos());
    }

    @Test
    @DisplayName("a patch that lands while the catalogue reloads waits for it and is not lost")
    void patchDuringTheReloadIsNotLost() throws Exception {
        List<Product> catalogo = List.of(producto("Lleno", "u/1"), producto("Lleno", "u/2"));
        when(aggregator.agregar(anyList(), anyBoolean(), any())).thenReturn(foto(catalogo, Map.of()));
        ScraperService s = servicio();
        var parcheoTerminoDuranteLaRecarga = new AtomicBoolean();
        var parche = new ArrayList<Thread>();
        var lecturas = new java.util.concurrent.atomic.AtomicInteger();
        when(productos.cargarProductos()).thenAnswer(i -> {
            // The first read is the per-site refresh while scraping; the second is the closing reload.
            if (lecturas.incrementAndGet() < 2) return catalogo;
            Thread t = Thread.ofVirtual().start(() -> s.eliminarProductoDeMemoria("u/2"));
            parche.add(t);
            t.join(300);
            parcheoTerminoDuranteLaRecarga.set(!t.isAlive());
            return catalogo;
        });
        when(aggregator.fromDBParcial(anyList(), any(), anySet())).thenReturn(foto(catalogo, Map.of()));

        s.iniciarScraping(Set.of());
        esperar(s);
        parche.get(0).join(5_000);

        assertThat(parcheoTerminoDuranteLaRecarga).isFalse();
        assertThat(s.getLastResult().productos()).extracting(Product::url).containsExactly("u/1");
    }
}
