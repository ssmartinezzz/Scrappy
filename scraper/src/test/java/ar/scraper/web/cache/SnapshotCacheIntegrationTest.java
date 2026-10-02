package ar.scraper.web.cache;

import ar.scraper.aggregator.CatalogSnapshotPort;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.grouping.JaccardSimilarity;
import ar.scraper.aggregator.grouping.ProductIdentity;
import ar.scraper.config.CacheConfig;
import ar.scraper.config.ScraperConfig;
import ar.scraper.model.Product;
import ar.scraper.web.ScraperService;
import ar.scraper.web.cache.CatalogoDerivadoCache.GruposKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A real {@link ScraperService} publishing through a real context: a mutation must reach the caches. */
class SnapshotCacheIntegrationTest {

    private static final String URL_A = "https://site.com/a";
    private static final String URL_B = "https://site.com/b";

    private AnnotationConfigApplicationContext ctx;
    private ScraperService service;
    private CatalogoDerivadoCache cache;

    @BeforeEach
    void setUp() {
        service = new ScraperService(Mockito.mock(ScraperConfig.class), Mockito.mock(ResultAggregator.class),
                Mockito.mock(ar.scraper.scrape.ScrapeRunPort.class),
                Mockito.mock(ar.scraper.classification.SitiosPort.class),
                Mockito.mock(ar.scraper.ml.MlOutputPort.class),
                Mockito.mock(ar.scraper.classification.SiteRegistry.class),
                Mockito.mock(ar.scraper.catalog.ProductPort.class),
                Mockito.mock(ar.scraper.pcs.TechSpecsIndexer.class),
                evento -> ctx.publishEvent(evento));
        ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(CatalogSnapshotPort.class, () -> service);
        ctx.registerBean(GroupingService.class, () -> new GroupingService(new ProductIdentity(), new JaccardSimilarity()));
        ctx.register(CacheConfig.class, CatalogoDerivadoCache.class, CatalogCacheEvictor.class);
        ctx.refresh();
        cache = ctx.getBean(CatalogoDerivadoCache.class);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    private static Product producto(String url, String nombre) {
        return Product.builder()
                .sitio("Sitio")
                .nombre(nombre)
                .precio(1000)
                .precioOriginal(null)
                .url(url)
                .imagenUrl("img")
                .categoria("Remera")
                .genero("unisex")
                .talles(List.of("M"))
                .ml(Product.MlScore.EMPTY)
                .marca("Nike")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1)
                .subCategoria("")
                .visual(Product.VisualAttrs.EMPTY)
                .build();
    }

    private static AggregatedResult catalogo(Product... productos) {
        List<Product> lista = List.of(productos);
        return new AggregatedResult(lista, Map.of(), Map.of(), ResultAggregator.calcularFacets(lista), 0, 0);
    }

    private List<ar.scraper.aggregator.grouping.ProductGroup> grupos() {
        return cache.grupos(GruposKey.de(service.snapshotVersion(), "", "", "", false));
    }

    @Test
    void aSoftDeleteIsVisibleInTheNextRequest() {
        service.setLastResultParaTest(catalogo(producto(URL_A, "Remera Azul Lisa"), producto(URL_B, "Buzo Canguro Gris")));
        assertThat(grupos()).hasSize(2);

        service.eliminarProductoDeMemoria(URL_A);

        assertThat(grupos()).hasSize(1);
    }

    @Test
    void aWipedCatalogStopsBeingServedFromTheCache() {
        service.setLastResultParaTest(catalogo(producto(URL_A, "Remera Azul Lisa")));
        assertThat(grupos()).hasSize(1);

        service.clearLastResult();

        assertThat(grupos()).isEmpty();
    }

    @Test
    void anUnchangedCatalogKeepsServingTheCachedList() {
        service.setLastResultParaTest(catalogo(producto(URL_A, "Remera Azul Lisa")));
        var primero = grupos();

        assertThat(grupos()).isSameAs(primero);
    }
}
