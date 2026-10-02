package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.cache.CatalogoDerivadoCache.MarcasKey;
import ar.scraper.web.cache.CatalogoDerivadoCache.MejoresKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The brand browser and the per-category picks ask the cache bean, keyed by the snapshot version. */
class MarcasPicksCacheTest {

    private ScraperService service;
    private CatalogoDerivadoCache derivados;
    private MarcasPicksController endpoints;

    @BeforeEach
    void setUp() {
        service = mock(ScraperService.class);
        derivados = mock(CatalogoDerivadoCache.class);
        endpoints = new MarcasPicksController(service, derivados);
        Product p = Product.builder()
                .sitio("Sitio")
                .nombre("Remera")
                .precio(1000)
                .precioOriginal(null)
                .url("https://s/1")
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
        when(service.getLastResult()).thenReturn(new AggregatedResult(List.of(p), Map.of(), Map.of(),
                ResultAggregator.calcularFacets(List.of(p)), 0, 0));
        when(service.snapshotVersion()).thenReturn(7L);
    }

    @Test
    void brandsComeFromTheCacheBean() {
        when(derivados.marcas(Mockito.any())).thenReturn(List.of());

        var respuesta = endpoints.marcasBrowser("Indumentaria", "NIKE", "count");

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        verify(derivados).marcas(MarcasKey.de(7, "indumentaria", "nike", "count"));
    }

    @Test
    void picksComeFromTheCacheBean() {
        when(derivados.mejores(Mockito.any())).thenReturn(List.of());

        var respuesta = endpoints.mejoresPorCategoria("Indumentaria");

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        verify(derivados).mejores(MejoresKey.de(7, "indumentaria"));
    }

    @Test
    void noSnapshotIsNoContentWithoutTouchingTheCache() {
        when(service.getLastResult()).thenReturn(null);

        assertThat(endpoints.marcasBrowser(null, null, "count").getStatusCode().value()).isEqualTo(204);
        assertThat(endpoints.mejoresPorCategoria(null).getStatusCode().value()).isEqualTo(204);
        Mockito.verifyNoInteractions(derivados);
    }
}
