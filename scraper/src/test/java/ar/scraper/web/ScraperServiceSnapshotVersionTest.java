package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.CatalogoActualizado;
import ar.scraper.config.ScraperConfig;
import ar.scraper.ml.FinanciacionEnricher;
import ar.scraper.model.Product;
import ar.scraper.scrape.ScrapeRunPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Every change to what readers are served bumps the snapshot version and announces it. */
class ScraperServiceSnapshotVersionTest {

    private static final String URL_A = "https://site.com/a";
    private static final String URL_B = "https://site.com/b";

    private final List<Object> eventos = new ArrayList<>();
    private ResultAggregator aggregator;
    private ScraperService service;

    @BeforeEach
    void setUp() throws Exception {
        aggregator = Mockito.mock(ResultAggregator.class);
        var scrapeRun = Mockito.mock(ScrapeRunPort.class);
        Mockito.when(scrapeRun.crear(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.any())).thenReturn(7L);
        Mockito.when(scrapeRun.startedAtDe(7L)).thenReturn(Optional.of(Instant.parse("2026-08-26T10:00:00Z")));
        Mockito.when(scrapeRun.existeCorridaCompletada()).thenReturn(true);
        service = new ScraperService(Mockito.mock(ScraperConfig.class), aggregator, scrapeRun,
                Mockito.mock(ar.scraper.classification.SitiosPort.class),
                Mockito.mock(ar.scraper.ml.MlOutputPort.class),
                Mockito.mock(ar.scraper.classification.SiteRegistry.class),
                Mockito.mock(ar.scraper.catalog.ProductPort.class),
                Mockito.mock(ar.scraper.pcs.TechSpecsIndexer.class),
                eventos::add);
    }

    private static Product producto(String url, String categoria) {
        return new Product("Sitio", "Producto", 1000, null, url, "",
                categoria, "unisex", List.of("M"), Product.MlScore.EMPTY, "Marca",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1, "Sub", null);
    }

    private static AggregatedResult catalogo(Product... productos) {
        List<Product> lista = List.of(productos);
        return new AggregatedResult(lista, Map.of("Sitio", lista.size()), Map.of(),
                ResultAggregator.calcularFacets(lista), 1000, 1000);
    }

    private void abrir() {
        service.abrirRun(List.of(new ScraperConfig.SiteConfig("Sitio", "https://site.com", "indumentaria")));
    }

    @Test
    void startsAtZeroAndAnnouncesNothing() {
        assertThat(service.snapshotVersion()).isZero();
        assertThat(eventos).isEmpty();
    }

    @Test
    void replacingTheCatalogAnnouncesTheNewVersion() {
        service.setLastResultParaTest(catalogo(producto(URL_A, "Camisa")));
        service.setLastResultParaTest(catalogo(producto(URL_B, "Remera")));

        assertThat(service.snapshotVersion()).isEqualTo(2);
        assertThat(eventos).containsExactly(new CatalogoActualizado(1), new CatalogoActualizado(2));
    }

    @Test
    void settingTheSameSnapshotAgainIsNotAChange() {
        AggregatedResult foto = catalogo(producto(URL_A, "Camisa"));
        service.setLastResultParaTest(foto);
        service.setLastResultParaTest(foto);

        assertThat(service.snapshotVersion()).isEqualTo(1);
    }

    @Test
    void theProgressiveRebuildDuringARunIsNotAnnouncedBecauseReadersDoNotSeeIt() {
        service.setLastResultParaTest(catalogo(producto(URL_A, "Camisa")));
        abrir();
        long antes = service.snapshotVersion();

        service.setLastResultParaTest(catalogo(producto(URL_B, "Remera")));

        assertThat(service.snapshotVersion()).isEqualTo(antes);
    }

    @Test
    void closingARunThatRebuiltTheCatalogAnnouncesTheSwap() {
        service.setLastResultParaTest(catalogo(producto(URL_A, "Camisa")));
        abrir();
        service.setLastResultParaTest(catalogo(producto(URL_B, "Remera")));
        long antes = service.snapshotVersion();

        service.cerrarRun("COMPLETED", 1);

        assertThat(service.snapshotVersion()).isEqualTo(antes + 1);
    }

    @Test
    void aManualSoftDeleteIsAnnounced() {
        service.setLastResultParaTest(catalogo(producto(URL_A, "Camisa"), producto(URL_B, "Remera")));
        long antes = service.snapshotVersion();

        service.eliminarProductoDeMemoria(URL_A);

        assertThat(service.snapshotVersion()).isEqualTo(antes + 1);
    }

    @Test
    void deletingAnUnknownProductWhenNothingIsLoadedAnnouncesNothing() {
        service.eliminarProductoDeMemoria(URL_A);

        assertThat(service.snapshotVersion()).isZero();
    }

    @Test
    void anAgentReclassificationIsAnnounced() {
        service.setLastResultParaTest(catalogo(producto(URL_A, "Camisa")));
        long antes = service.snapshotVersion();

        service.actualizarProductoEnMemoria(URL_A, "Remera", null, null, null, null);

        assertThat(service.snapshotVersion()).isEqualTo(antes + 1);
    }

    @Test
    void aFinancingRecomputeIsAnnounced() {
        service.setLastResultParaTest(catalogo(producto(URL_A, "Camisa")));
        FinanciacionEnricher enricher = Mockito.mock(FinanciacionEnricher.class);
        Mockito.when(enricher.enriquecer(Mockito.anyList())).thenReturn(List.of(producto(URL_A, "Camisa")));
        Mockito.when(aggregator.financiacionEnricher()).thenReturn(enricher);
        long antes = service.snapshotVersion();

        service.recomputarFinanciacion(aggregator);

        assertThat(service.snapshotVersion()).isEqualTo(antes + 1);
    }

    @Test
    void wipingTheCatalogIsAnnouncedOnceAndWipingAnEmptyOneIsNot() {
        service.setLastResultParaTest(catalogo(producto(URL_A, "Camisa")));
        long antes = service.snapshotVersion();

        service.clearLastResult();
        service.clearLastResult();

        assertThat(service.snapshotVersion()).isEqualTo(antes + 1);
    }

    @Test
    void theEventCarriesTheVersionTheAccessorReports() {
        service.setLastResultParaTest(catalogo(producto(URL_A, "Camisa")));

        assertThat(eventos).singleElement()
                .isEqualTo(new CatalogoActualizado(service.snapshotVersion()));
    }
}
