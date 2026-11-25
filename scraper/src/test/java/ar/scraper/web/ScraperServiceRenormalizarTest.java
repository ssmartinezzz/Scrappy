package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.api.ApiException;
import ar.scraper.catalog.ProductPort;
import ar.scraper.config.ScraperConfig;
import ar.scraper.ml.PythonRunner;
import ar.scraper.model.Product;
import ar.scraper.scrape.ScrapeRunPort;
import ar.scraper.scrape.ScraperStatus;
import ar.scraper.scrape.StatusEvent;
import ar.scraper.scrape.StatusEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ScraperService.renormalizarCatalogo — the scrape turn and the scoring slot")
class ScraperServiceRenormalizarTest {

    private static final Map<String, Integer> RESULTADO = Map.of("escriturasAplicadas", 1);

    private final List<StatusEvent> heard = new CopyOnWriteArrayList<>();
    private final PythonRunner runner = new PythonRunner();
    private ResultAggregator aggregator;
    private ProductPort productos;
    private ScraperService service;

    @BeforeEach
    void setUp() {
        aggregator = Mockito.mock(ResultAggregator.class);
        productos = Mockito.mock(ProductPort.class);
        when(aggregator.getPythonRunner()).thenReturn(runner);
        when(aggregator.renormalizarCatalogo()).thenReturn(RESULTADO);
        when(aggregator.fromDB(anyList())).thenReturn(catalogo("nuevo"));
        StatusEvents recorder = new StatusEvents() {
            @Override
            public void publish(StatusEvent event) {
                heard.add(event);
            }

            @Override
            public Subscription subscribe(java.util.function.Consumer<StatusEvent> listener) {
                return StatusEvents.NONE.subscribe(listener);
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

    private static AggregatedResult catalogo(String nombre) {
        Product p = Product.builder().sitio("Sitio").nombre(nombre).precio(1000).url("https://site.com/" + nombre)
                .imagenUrl("").categoria("Remera").genero("").talles(List.of()).build();
        List<Product> lista = List.of(p);
        return new AggregatedResult(lista, Map.of("Sitio", 1), Map.of(),
                ResultAggregator.calcularFacets(lista), 1000, 1000);
    }

    private static ApiException conflicto(Runnable accion) {
        AtomicReference<ApiException> visto = new AtomicReference<>();
        assertThatThrownBy(accion::run).isInstanceOfSatisfying(ApiException.class, visto::set);
        assertThat(visto.get().status().value()).isEqualTo(409);
        return visto.get();
    }

    @Test
    @DisplayName("success: the memory is swapped after the write, and the previous status and message come back")
    void successSwapsMemoryAndRestoresStatus() {
        service.setLastResultParaTest(catalogo("viejo"));
        long version = service.snapshotVersion();

        assertThat(service.renormalizarCatalogo()).isEqualTo(RESULTADO);

        assertThat(service.getLastResult().productos()).extracting(Product::nombre).containsExactly("nuevo");
        assertThat(service.snapshotVersion()).isGreaterThan(version);
        assertThat(service.getStatus()).isEqualTo(ScraperStatus.IDLE);
        assertThat(service.getStatusMsg()).isEqualTo("Listo");
        assertThat(heard).extracting(e -> ((StatusEvent.ScrapeStatus) e).status())
                .containsExactly(ScraperStatus.RUNNING, ScraperStatus.IDLE);
    }

    @Test
    @DisplayName("with no catalog in memory yet, it is loaded after the write")
    void loadsTheCatalogWhenThereWasNone() {
        assertThat(service.getLastResult()).isNull();

        service.renormalizarCatalogo();

        assertThat(service.getLastResult()).isNotNull();
    }

    @Test
    @DisplayName("a failed write propagates, keeps the old snapshot and restores the status")
    void failureKeepsTheOldSnapshotAndRestoresStatus() {
        service.setLastResultParaTest(catalogo("viejo"));
        when(aggregator.renormalizarCatalogo()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.renormalizarCatalogo()).hasMessage("boom");

        assertThat(service.getLastResult().productos()).extracting(Product::nombre).containsExactly("viejo");
        verify(aggregator, never()).fromDB(anyList());
        assertThat(service.getStatus()).isEqualTo(ScraperStatus.IDLE);
        assertThat(service.getStatusMsg()).isEqualTo("Listo");
    }

    @Test
    @DisplayName("an Error restores the status too")
    void anErrorRestoresTheStatus() {
        when(aggregator.renormalizarCatalogo()).thenThrow(new StackOverflowError());

        assertThatThrownBy(() -> service.renormalizarCatalogo()).isInstanceOf(StackOverflowError.class);

        assertThat(service.getStatus()).isEqualTo(ScraperStatus.IDLE);
    }

    @Test
    @DisplayName("a failed reload after the commit keeps the old snapshot and still answers")
    void failedReloadKeepsTheOldSnapshot() {
        service.setLastResultParaTest(catalogo("viejo"));
        when(aggregator.fromDB(anyList())).thenThrow(new IllegalStateException("sin memoria"));

        assertThat(service.renormalizarCatalogo()).isEqualTo(RESULTADO);

        assertThat(service.getLastResult().productos()).extracting(Product::nombre).containsExactly("viejo");
        assertThat(service.getStatus()).isEqualTo(ScraperStatus.IDLE);
    }

    @Test
    @DisplayName("scoring slot busy: 409 ml_en_curso, nothing written, nothing swapped, turn released")
    void scoringSlotBusyAnswers409() {
        service.setLastResultParaTest(catalogo("viejo"));
        AtomicReference<ApiException> visto = new AtomicReference<>();

        runner.conSlotDeScoring(() -> visto.set(conflicto(() -> service.renormalizarCatalogo())));

        assertThat(visto.get().code()).isEqualTo("ml_en_curso");
        verify(aggregator, never()).renormalizarCatalogo();
        verify(aggregator, never()).fromDB(anyList());
        assertThat(service.getLastResult().productos()).extracting(Product::nombre).containsExactly("viejo");
        assertThat(service.getStatus()).isEqualTo(ScraperStatus.IDLE);
        assertThat(service.getStatusMsg()).isEqualTo("Listo");
    }

    @Test
    @DisplayName("while it runs: another one, a scrape and a resume cannot start (409 scrape_en_curso); afterwards they can")
    void aScrapeCannotStartWhileItRuns() throws Exception {
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch seguir = new CountDownLatch(1);
        when(aggregator.renormalizarCatalogo()).thenAnswer(inv -> {
            dentro.countDown();
            assertThat(seguir.await(10, TimeUnit.SECONDS)).isTrue();
            return RESULTADO;
        });
        Thread primera = Thread.ofVirtual().start(() -> service.renormalizarCatalogo());
        assertThat(dentro.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(service.getStatus()).isEqualTo(ScraperStatus.RUNNING);
            assertThat(conflicto(() -> service.renormalizarCatalogo()).code()).isEqualTo("scrape_en_curso");
            assertThat(service.iniciarScraping(Set.of())).isFalse();
            assertThat(service.reanudar()).isFalse();
            assertThat(service.cancelar()).isFalse();
            assertThat(service.estaCancelado()).isFalse();
        } finally {
            seguir.countDown();
            primera.join(10_000);
        }

        assertThat(service.getStatus()).isEqualTo(ScraperStatus.IDLE);
        assertThat(service.estaCancelado()).isFalse();
        assertThat(service.iniciarScraping(Set.of())).isTrue();
    }
}
