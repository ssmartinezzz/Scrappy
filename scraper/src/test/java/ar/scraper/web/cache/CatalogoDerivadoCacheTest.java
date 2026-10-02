package ar.scraper.web.cache;

import ar.scraper.aggregator.CatalogSnapshotPort;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.grouping.JaccardSimilarity;
import ar.scraper.aggregator.grouping.ProductIdentity;
import ar.scraper.catalog.CatalogoActualizado;
import ar.scraper.config.CacheConfig;
import ar.scraper.config.CacheNames;
import ar.scraper.model.Product;
import ar.scraper.web.cache.CatalogoDerivadoCache.GruposKey;
import ar.scraper.web.cache.CatalogoDerivadoCache.MarcasKey;
import ar.scraper.web.cache.CatalogoDerivadoCache.MejoresKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogoDerivadoCacheTest {

    private final AtomicReference<AggregatedResult> foto = new AtomicReference<>();
    private GroupingService grouping;
    private AnnotationConfigApplicationContext ctx;
    private CatalogoDerivadoCache cache;

    @BeforeEach
    void setUp() {
        grouping = Mockito.spy(new GroupingService(new ProductIdentity(), new JaccardSimilarity()));
        foto.set(catalogo(producto("Sitio A", "Zapatilla Nike Air", "Calzado", "indumentaria"),
                producto("Sitio B", "Zapatilla Nike Air", "Calzado", "indumentaria"),
                producto("Sitio A", "Remera Lisa", "Remera", "indumentaria")));
        ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(CatalogSnapshotPort.class, () -> foto::get);
        ctx.registerBean(GroupingService.class, () -> grouping);
        ctx.register(CacheConfig.class, CatalogoDerivadoCache.class, CatalogCacheEvictor.class);
        ctx.refresh();
        cache = ctx.getBean(CatalogoDerivadoCache.class);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        ctx.close();
    }

    private static Product producto(String sitio, String nombre, String categoria, String rubro) {
        return Product.builder()
                .sitio(sitio)
                .nombre(nombre)
                .precio(1000)
                .precioOriginal(null)
                .url("https://" + sitio.replace(' ', '-') + "/" + nombre.hashCode())
                .imagenUrl("img")
                .categoria(categoria)
                .genero("unisex")
                .talles(List.of("M"))
                .ml(Product.MlScore.EMPTY)
                .marca("Nike")
                .rubro(rubro)
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

    private static GruposKey clave(long ver) {
        return GruposKey.de(ver, "", "", "", false);
    }

    @Test
    void theBeanIsAnAopProxyAndTheManagerHasTheCaches() {
        assertThat(AopUtils.isAopProxy(cache)).isTrue();
        assertThat(ctx.getBean(CacheManager.class).getCacheNames()).contains(CacheNames.GRUPOS);
    }

    @Test
    void theSecondCallWithTheSameKeyDoesNotRegroup() {
        var primero = cache.grupos(clave(1));
        var segundo = cache.grupos(clave(1));

        assertThat(segundo).isSameAs(primero);
        Mockito.verify(grouping, Mockito.times(1)).agrupar(Mockito.anyList(), Mockito.anyBoolean());
    }

    @Test
    void aNewSnapshotVersionRecomputes() {
        cache.grupos(clave(1));
        cache.grupos(clave(2));

        Mockito.verify(grouping, Mockito.times(2)).agrupar(Mockito.anyList(), Mockito.anyBoolean());
    }

    @Test
    void theSameFilterInAnotherCaseSharesTheEntry() {
        var a = cache.grupos(GruposKey.de(1, "NIKE", "CALZADO", "Indumentaria", false));
        var b = cache.grupos(GruposKey.de(1, "nike", "calzado", "indumentaria", false));

        assertThat(b).isSameAs(a);
        Mockito.verify(grouping, Mockito.times(1)).agrupar(Mockito.anyList(), Mockito.anyBoolean());
    }

    @Test
    void blankAndNullFiltersAreTheSameKey() {
        assertThat(GruposKey.de(1, null, "  ", "", false)).isEqualTo(GruposKey.de(1, "", "", null, false));
    }

    @Test
    void filtersDoNotTrimBecauseTheEndpointFiltersDoNot() {
        assertThat(GruposKey.de(1, " nike", "", "", false)).isNotEqualTo(GruposKey.de(1, "nike", "", "", false));
    }

    @Test
    void anotherFilterOrMultiSiteFlagIsAnotherEntry() {
        cache.grupos(clave(1));
        cache.grupos(GruposKey.de(1, "remera", "", "", false));
        cache.grupos(GruposKey.de(1, "", "", "", true));

        Mockito.verify(grouping, Mockito.times(3)).agrupar(Mockito.anyList(), Mockito.anyBoolean());
    }

    @Test
    void filtersApplyBeforeGrouping() {
        var grupos = cache.grupos(GruposKey.de(1, "remera", "", "", false));

        assertThat(grupos).hasSize(1);
        assertThat(grupos.get(0).getNombre()).containsIgnoringCase("remera");
    }

    @Test
    void theCachedListCannotBeMutatedByCallers() {
        var grupos = cache.grupos(clave(1));

        assertThatThrownBy(grupos::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aCatalogChangeEventEmptiesEveryCache() {
        cache.grupos(clave(1));
        ctx.publishEvent(new CatalogoActualizado(2));
        cache.grupos(clave(1));

        Mockito.verify(grouping, Mockito.times(2)).agrupar(Mockito.anyList(), Mockito.anyBoolean());
    }

    @Test
    void noSnapshotGroupsToNothingAndTheNextVersionSeesTheLoadedOne() {
        foto.set(null);
        assertThat(cache.grupos(clave(1))).isEmpty();

        foto.set(catalogo(producto("Sitio A", "Remera Lisa", "Remera", "indumentaria")));

        assertThat(cache.grupos(clave(2))).hasSize(1);
    }

    @Test
    void concurrentCallersForTheSameKeyShareOneComputation() throws Exception {
        int hilos = 8;
        var pool = Executors.newFixedThreadPool(hilos);
        var listos = new CountDownLatch(hilos);
        var arranque = new CountDownLatch(1);
        try {
            var futuros = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < hilos; i++) {
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    arranque.await();
                    return cache.grupos(clave(9));
                }));
            }
            listos.await();
            arranque.countDown();
            for (var f : futuros) f.get();
        } finally {
            pool.shutdownNow();
        }

        Mockito.verify(grouping, Mockito.times(1)).agrupar(Mockito.anyList(), Mockito.anyBoolean());
    }

    @Test
    void onlyTheDerivedCacheBeanCarriesCacheAnnotations() throws Exception {
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        var violaciones = new java.util.ArrayList<String>();
        for (var d : scanner.findCandidateComponents("ar/scraper".replace('/', '.'))) {
            Class<?> tipo = Class.forName(d.getBeanClassName());
            if (tipo == CatalogoDerivadoCache.class) continue;
            for (var m : tipo.getDeclaredMethods()) {
                if (m.isAnnotationPresent(org.springframework.cache.annotation.Cacheable.class)
                        || m.isAnnotationPresent(org.springframework.cache.annotation.CacheEvict.class)
                        || m.isAnnotationPresent(org.springframework.cache.annotation.CachePut.class)) {
                    violaciones.add(tipo.getName() + "#" + m.getName());
                }
            }
        }
        assertThat(violaciones).isEmpty();
    }

    @Test
    void brandBrowserIsCachedPerVersionAndNormalizedFilters() {
        var a = cache.marcas(MarcasKey.de(1, "Indumentaria", "NIKE", "count"));
        var b = cache.marcas(MarcasKey.de(1, "indumentaria", "nike", "count"));
        var otraVersion = cache.marcas(MarcasKey.de(2, "indumentaria", "nike", "count"));

        assertThat(a).hasSize(1);
        assertThat(b).isSameAs(a);
        assertThat(otraVersion).isNotSameAs(a).hasSameSizeAs(a);
    }

    @Test
    void brandBrowserSortIsPartOfTheKey() {
        var porCantidad = cache.marcas(MarcasKey.de(1, "", "", "count"));
        var porPrecio = cache.marcas(MarcasKey.de(1, "", "", "precio_asc"));

        assertThat(porPrecio).isNotSameAs(porCantidad);
    }

    @Test
    void bestPerCategoryIsCachedPerVersionAndRubro() {
        var a = cache.mejores(MejoresKey.de(1, "INDUMENTARIA"));
        var b = cache.mejores(MejoresKey.de(1, "indumentaria"));

        assertThat(a).isNotEmpty();
        assertThat(b).isSameAs(a);
        assertThat(cache.mejores(MejoresKey.de(2, "indumentaria"))).isNotSameAs(a);
    }

    @Test
    void theChangeEventEmptiesTheBrandAndBestCachesToo() {
        var marcas = cache.marcas(MarcasKey.de(1, "", "", "count"));
        var mejores = cache.mejores(MejoresKey.de(1, ""));

        ctx.publishEvent(new CatalogoActualizado(2));

        assertThat(cache.marcas(MarcasKey.de(1, "", "", "count"))).isNotSameAs(marcas);
        assertThat(cache.mejores(MejoresKey.de(1, ""))).isNotSameAs(mejores);
    }

    @Test
    void noSnapshotGivesEmptyBrandAndBestViews() {
        foto.set(null);

        assertThat(cache.marcas(MarcasKey.de(1, "", "", "count"))).isEmpty();
        assertThat(cache.mejores(MejoresKey.de(1, ""))).isEmpty();
    }
}
