package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.scrape.ScrapeRunPort;
import ar.scraper.scrape.ScraperStatus;
import ar.scraper.scrape.StatusEvent;
import ar.scraper.scrape.StatusEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Every status mutation of the scrape service reaches the bus, in order. */
class ScraperServiceStatusEventsTest {

    private final List<StatusEvent> heard = new CopyOnWriteArrayList<>();
    private ResultAggregator aggregator;
    private ar.scraper.catalog.ProductPort productos;
    private ScraperService service;

    @BeforeEach
    void setUp() {
        aggregator = Mockito.mock(ResultAggregator.class);
        productos = Mockito.mock(ar.scraper.catalog.ProductPort.class);
        StatusEvents bus = StatusEvents.NONE;
        StatusEvents recorder = new StatusEvents() {
            @Override
            public void publish(StatusEvent event) {
                heard.add(event);
            }

            @Override
            public Subscription subscribe(java.util.function.Consumer<StatusEvent> listener) {
                return bus.subscribe(listener);
            }
        };
        service = new ScraperService(Mockito.mock(ScraperConfig.class), aggregator,
                Mockito.mock(ScrapeRunPort.class),
                Mockito.mock(ar.scraper.classification.SitiosPort.class),
                Mockito.mock(ar.scraper.ml.MlOutputPort.class),
                Mockito.mock(ar.scraper.classification.SiteRegistry.class),
                productos,
                Mockito.mock(ar.scraper.pcs.TechSpecsIndexer.class),
                e -> { }, recorder);
    }

    @Test
    void startingARunAnnouncesRunningThenTheTerminalState() throws Exception {
        assertThat(service.iniciarScraping(Set.of())).isTrue();

        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && heard.stream().noneMatch(e -> e instanceof StatusEvent.ScrapeStatus s
                        && s.status() == ScraperStatus.DONE)) {
            Thread.sleep(10);
        }

        assertThat(heard).containsExactly(
                new StatusEvent.ScrapeStatus(ScraperStatus.RUNNING, "Iniciando scrapers..."),
                new StatusEvent.ScrapeStatus(ScraperStatus.DONE, "No había sitios que scrapear"));
        assertThat(service.getStatus()).isEqualTo(ScraperStatus.DONE);
        assertThat(service.getStatusMsg()).isEqualTo("No había sitios que scrapear");
    }

    @Test
    void restoringTheCatalogFromTheDatabaseAnnouncesDone() throws Exception {
        Product p = Product.builder()
                .sitio("Sitio")
                .nombre("Producto")
                .precio(1000)
                .precioOriginal(null)
                .url("https://site.com/a")
                .imagenUrl("")
                .categoria("Camisa")
                .genero("unisex")
                .talles(List.of("M"))
                .ml(Product.MlScore.EMPTY)
                .marca("Marca")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1)
                .subCategoria("Sub")
                .visual(null)
                .build();
        Mockito.when(productos.cargarProductos()).thenReturn(List.of(p));
        Mockito.when(aggregator.fromDB(Mockito.any())).thenReturn(new ResultAggregator.AggregatedResult(
                List.of(p), java.util.Map.of("Sitio", 1), java.util.Map.of(),
                ResultAggregator.calcularFacets(List.of(p)), 1000, 1000));

        service.cargarDesdeBD();

        assertThat(heard).containsExactly(
                new StatusEvent.ScrapeStatus(ScraperStatus.DONE, "Datos restaurados: 1 productos"));
    }

    @Test
    void cancellingAnIdleServiceAnnouncesNothing() {
        assertThat(service.cancelar()).isFalse();
        assertThat(heard).isEmpty();
    }
}
