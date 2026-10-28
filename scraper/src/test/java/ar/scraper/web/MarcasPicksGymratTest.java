package ar.scraper.web;

import ar.scraper.aggregator.CatalogSnapshotPort;
import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.grouping.JaccardSimilarity;
import ar.scraper.aggregator.grouping.ProductIdentity;
import ar.scraper.config.CacheConfig;
import ar.scraper.model.Product;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.cache.CatalogoDerivadoCache.MejoresKey;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;

import static ar.scraper.web.support.Wire.data;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code gymrat} is an orthogonal filter on /api/mejores: it never becomes a rubro. */
class MarcasPicksGymratTest {

    private ScraperService service;
    private MarcasPicksController controller;
    private AnnotationConfigApplicationContext ctx;
    private CatalogoDerivadoCache cache;

    @BeforeEach
    void setUp() {
        var catalogo = catalogo(
                producto("a", "Remera", "indumentaria", true, "Fuark", "Fuark"),
                producto("b", "Remera", "indumentaria", false, "Sitio", "Marca"),
                producto("c", "Remera", "indumentaria", false, "Sitio", "Marca"),
                producto("d", "Proteina", "suplementos", true, "Vaypol", "Under Armour"),
                producto("e", "Proteina", "suplementos", false, "Sitio", "Marca"),
                // gymrat by the tagger, but not one of the /picks gym brands
                producto("f", "Remera", "indumentaria", true, "Vaypol", "Nike"),
                producto("g", "Short", "indumentaria", true, "Vaypol", "adidas"));
        service = mock(ScraperService.class);
        when(service.getLastResult()).thenReturn(catalogo);
        when(service.snapshotVersion()).thenReturn(1L);
        controller = new MarcasPicksController(service,
                new CatalogoDerivadoCache(service, mock(GroupingService.class)));

        ctx = new AnnotationConfigApplicationContext();
        ctx.registerBean(CatalogSnapshotPort.class, () -> () -> catalogo);
        ctx.registerBean(GroupingService.class, () -> new GroupingService(new ProductIdentity(), new JaccardSimilarity()));
        ctx.register(CacheConfig.class, CatalogoDerivadoCache.class);
        ctx.refresh();
        cache = ctx.getBean(CatalogoDerivadoCache.class);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    private static Product producto(String id, String categoria, String rubro, boolean gymrat,
                                    String sitio, String marca) {
        return Product.builder()
                .sitio(sitio).nombre(categoria + " " + id).precio(1000).precioOriginal(null)
                .url("https://s/" + id).imagenUrl("img").categoria(categoria).genero("unisex")
                .talles(List.of("M")).ml(Product.MlScore.EMPTY).marca(marca).rubro(rubro)
                .gymrat(gymrat).marcaPremium(false).senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY).cantidadUnidades(1).subCategoria("")
                .visual(Product.VisualAttrs.EMPTY).build();
    }

    private static AggregatedResult catalogo(Product... productos) {
        List<Product> lista = List.of(productos);
        return new AggregatedResult(lista, Map.of(), Map.of(), ResultAggregator.calcularFacets(lista), 0, 0);
    }

    private static int count(JsonNode body, String categoria) {
        for (JsonNode n : body) {
            if (categoria.equals(n.path("categoria").asText())) return n.path("count").asInt();
        }
        return -1;
    }

    @Test
    void gymratTrueCountsOnlyGymratProductsAcrossRubros() {
        var body = data(controller.mejoresPorCategoria(null, true));

        assertThat(body).hasSize(2);
        assertThat(count(body, "Remera")).isEqualTo(1);
        assertThat(count(body, "Proteina")).isEqualTo(1);
        for (JsonNode card : body) {
            assertThat(card.path("picks").get(0).path("url").asText()).isIn("https://s/a", "https://s/d");
        }
    }

    @Test
    void gymratOnlyKeepsTheGymBrandsEvenWhenTheTaggerSaysGymrat() {
        var body = data(controller.mejoresPorCategoria("indumentaria", true));

        assertThat(count(body, "Remera")).isEqualTo(1);
        assertThat(count(body, "Short")).isEqualTo(-1);
    }

    @Test
    void gymratIntersectsWithRubro() {
        var body = data(controller.mejoresPorCategoria("suplementos", true));

        assertThat(body).hasSize(1);
        assertThat(count(body, "Proteina")).isEqualTo(1);
    }

    @Test
    void absentOrFalseGymratKeepsTheOldBehaviour() {
        var sinParam = data(controller.mejoresPorCategoria(null, null));
        var falso = data(controller.mejoresPorCategoria(null, false));

        assertThat(count(sinParam, "Remera")).isEqualTo(4);
        assertThat(count(sinParam, "Proteina")).isEqualTo(2);
        assertThat(falso).isEqualTo(sinParam);
    }

    @Test
    void theKeyTreatsNullAsFalseAndSeparatesTrue() {
        assertThat(MejoresKey.de(1, "", null)).isEqualTo(MejoresKey.de(1, "", false));
        assertThat(MejoresKey.de(1, "", true)).isNotEqualTo(MejoresKey.de(1, "", false));
        assertThat(MejoresKey.de(1, "")).isEqualTo(MejoresKey.de(1, "", false));
    }

    @Test
    void gymratAndPlainPicksAreDistinctCacheEntries() {
        var plano = cache.mejores(MejoresKey.de(1, "", false));
        var gym = cache.mejores(MejoresKey.de(1, "", true));

        assertThat(gym).isNotSameAs(plano);
        assertThat(cache.mejores(MejoresKey.de(1, "", true))).isSameAs(gym);
        assertThat(cache.mejores(MejoresKey.de(1, "", false))).isSameAs(plano);
        assertThat(gym.stream().mapToInt(c -> c.getCount()).sum()).isEqualTo(2);
        assertThat(plano.stream().mapToInt(c -> c.getCount()).sum()).isEqualTo(7);
    }
}
