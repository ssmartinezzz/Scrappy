package ar.scraper.web.cache;

import ar.scraper.aggregator.CatalogSnapshotPort;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.grouping.JaccardSimilarity;
import ar.scraper.aggregator.grouping.ProductIdentity;
import ar.scraper.api.ApiException;
import ar.scraper.catalog.CatalogoActualizado;
import ar.scraper.config.CacheConfig;
import ar.scraper.config.CacheNames;
import ar.scraper.model.Product;
import ar.scraper.web.cache.CatalogoDerivadoCache.MarcasKey;
import ar.scraper.web.cache.CatalogoDerivadoCache.MejoresKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The page methods run against the REAL Spring proxy and cache manager: the ranking must be computed once per key, not
 * once per request. Every ranking load reads the snapshot exactly once, so the reads count the computations.
 */
class CatalogoPaginasTest {

    private final AtomicInteger cargas = new AtomicInteger();
    private volatile AggregatedResult foto;
    private volatile CountDownLatch retenerCarga;
    private AnnotationConfigApplicationContext ctx;
    private CatalogoPaginas paginas;
    private CatalogoDerivadoCache derivados;

    @BeforeEach
    void setUp() {
        List<Product> lista = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lista.add(producto("https://s/" + i, i % 2 == 0 ? "Nike" : "Adidas", i % 3 == 0 ? "Remera" : "Buzo", i));
        }
        foto = new AggregatedResult(List.copyOf(lista), Map.of(), Map.of(), ResultAggregator.calcularFacets(lista), 0, 0);
        CatalogSnapshotPort puerto = () -> {
            cargas.incrementAndGet();
            var espera = retenerCarga;
            if (espera != null) {
                try {
                    espera.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return foto;
        };
        ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(CatalogSnapshotPort.class, () -> puerto);
        ctx.registerBean(ar.scraper.catalog.PoliticaStock.class, ar.scraper.catalog.PoliticaStock::porDefecto);
        ctx.registerBean(GroupingService.class, () -> new GroupingService(new ProductIdentity(), new JaccardSimilarity()));
        ctx.register(CacheConfig.class, CatalogoDerivadoCache.class, CatalogoPaginas.class, CatalogCacheEvictor.class);
        ctx.refresh();
        paginas = ctx.getBean(CatalogoPaginas.class);
        derivados = ctx.getBean(CatalogoDerivadoCache.class);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    private static Product producto(String url, String marca, String categoria, int scoreP) {
        return Product.builder()
                .sitio("Sitio")
                .nombre("Prenda " + url)
                .precio(1000 + scoreP)
                .precioOriginal(null)
                .url(url)
                .imagenUrl("img")
                .categoria(categoria)
                .genero("unisex")
                .talles(List.of("M"))
                .ml(new Product.MlScore(scoreP, List.of(), false, "estable", scoreP, 0.0, "standard"))
                .marca(marca)
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

    private static ApiException solicitudInvalida(Runnable accion) {
        var visto = new ApiException[1];
        assertThatThrownBy(accion::run).isInstanceOfSatisfying(ApiException.class, e -> visto[0] = e);
        assertThat(visto[0].status().value()).isEqualTo(400);
        assertThat(visto[0].code()).isEqualTo("solicitud_invalida");
        return visto[0];
    }

    private long entradas(String cache) {
        return ((CaffeineCache) ctx.getBean(CacheManager.class).getCache(cache)).getNativeCache().estimatedSize();
    }

    @Test
    void theCacheBeanIsAProxyTheBrandAndPicksPagesCallThrough() {
        assertThat(AopUtils.isAopProxy(derivados)).isTrue();
    }

    @Test
    void twoBrandPagesWithTheSameKeyRankOnce() {
        var k = MarcasKey.de(1, "", "", "count");

        var primera = paginas.marcasPagina(k, null, 1);
        var segunda = paginas.marcasPagina(k, primera.getNextCursor(), 1);

        assertThat(cargas.get()).isEqualTo(1);
        assertThat(primera.getMarcas()).hasSize(1);
        assertThat(segunda.getMarcas()).hasSize(1);
        assertThat(segunda.getMarcas().get(0).getMarca()).isNotEqualTo(primera.getMarcas().get(0).getMarca());
    }

    @Test
    void twoPicksPagesWithTheSameKeyRankOnce() {
        var k = MejoresKey.de(1, "", false, null);

        var primera = paginas.picks(k, "Remera", null, 3);
        var segunda = paginas.picks(k, "Remera", primera.getNextCursor(), 3);
        paginas.picks(k, "Buzo", "", null, 3);

        assertThat(cargas.get()).isEqualTo(1);
        assertThat(primera.getPicks()).hasSize(3);
        assertThat(segunda.getPicks()).hasSize(3);
        assertThat(segunda.getPicks().get(0).getUrl()).isNotEqualTo(primera.getPicks().get(0).getUrl());
    }

    @Test
    void anotherKeyRanksAgain() {
        paginas.marcasPagina(MarcasKey.de(1, "", "", "count"), null, 5);
        paginas.marcasPagina(MarcasKey.de(1, "", "", "nombre"), null, 5);
        paginas.picks(MejoresKey.de(1, "", false, null), "Remera", null, 5);
        paginas.picks(MejoresKey.de(1, "", false, "mujer"), "Remera", null, 5);

        assertThat(cargas.get()).isEqualTo(4);
    }

    @Test
    void aCatalogChangeEventEmptiesTheRankingsAndTheNextPageRanksAgain() {
        paginas.marcasPagina(MarcasKey.de(1, "", "", "count"), null, 5);
        paginas.picks(MejoresKey.de(1, "", false, null), "Remera", null, 5);
        assertThat(entradas(CacheNames.MARCAS)).isEqualTo(1);
        assertThat(entradas(CacheNames.MEJORES_RANKING)).isEqualTo(1);

        ctx.publishEvent(new CatalogoActualizado(2));

        assertThat(entradas(CacheNames.MARCAS)).isZero();
        assertThat(entradas(CacheNames.MEJORES_RANKING)).isZero();
        paginas.marcasPagina(MarcasKey.de(1, "", "", "count"), null, 5);
        paginas.picks(MejoresKey.de(1, "", false, null), "Remera", null, 5);
        assertThat(cargas.get()).isEqualTo(4);
    }

    @Test
    void anInvalidSortIsTheSameBadRequestThroughTheProxyAndIsNotCached() {
        var k = MarcasKey.de(1, "", "", "inventado");

        var error = solicitudInvalida(() -> paginas.marcasPagina(k, null, 10));
        solicitudInvalida(() -> paginas.marcasPagina(k, null, 10));

        assertThat(error.getMessage()).isEqualTo("sort inválido");
        assertThat(error.getCause()).isNull();
        assertThat(cargas.get()).isEqualTo(2);
        assertThat(entradas(CacheNames.MARCAS)).isZero();
    }

    @Test
    void aMalformedCursorIsABadRequestAfterTheCachedRanking() {
        var marcas = MarcasKey.de(1, "", "", "count");
        var picks = MejoresKey.de(1, "", false, null);

        solicitudInvalida(() -> paginas.marcasPagina(marcas, "no-es-base64!", 10));
        solicitudInvalida(() -> paginas.picks(picks, "Remera", "no-es-base64!", 10));
        solicitudInvalida(() -> paginas.marcasPagina(marcas, paginas.picks(picks, "Remera", null, 2).getNextCursor(), 10));

        assertThat(cargas.get()).isEqualTo(2);
    }

    @Test
    void aLoadStartedBeforeTheEvictionCannotServeTheNewVersionAndIsBoundedByTheTtl() throws Exception {
        retenerCarga = new CountDownLatch(1);
        var viejo = new Thread(() -> paginas.marcasPagina(MarcasKey.de(1, "", "", "count"), null, 5));
        viejo.start();
        while (cargas.get() == 0) Thread.onSpinWait();

        ctx.publishEvent(new CatalogoActualizado(2));
        retenerCarga.countDown();
        viejo.join();
        retenerCarga = null;

        paginas.marcasPagina(MarcasKey.de(2, "", "", "count"), null, 5);
        assertThat(cargas.get()).isEqualTo(2);
        var nativa = ((CaffeineCache) ctx.getBean(CacheManager.class).getCache(CacheNames.MARCAS)).getNativeCache();
        assertThat(nativa.policy().expireAfterWrite()).isPresent();
        assertThat(nativa.policy().eviction().orElseThrow().getMaximum()).isPositive();

        ctx.publishEvent(new CatalogoActualizado(3));
        assertThat(entradas(CacheNames.MARCAS)).isZero();
    }
}
