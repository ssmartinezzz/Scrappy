package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.grouping.ProductGroup;
import ar.scraper.catalog.PreciosExternosPort;
import ar.scraper.model.Product;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.cache.CatalogoDerivadoCache.GruposKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code /api/grupos} asks the cache bean for the grouped list, keyed by the snapshot version. */
class ComparadorGruposCacheTest {

    private ScraperService service;
    private CatalogoDerivadoCache derivados;
    private ComparadorController endpoints;

    @BeforeEach
    void setUp() {
        service = mock(ScraperService.class);
        derivados = mock(CatalogoDerivadoCache.class);
        endpoints = new ComparadorController(service, mock(PreciosExternosPort.class), derivados);
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
    }

    @Test
    void theGroupedListComesFromTheCacheBeanKeyedBySnapshotVersionAndNormalizedFilters() {
        when(service.snapshotVersion()).thenReturn(42L);
        when(derivados.grupos(Mockito.any())).thenReturn(List.<ProductGroup>of());

        endpoints.grupos("NIKE", "", "Remera", "", 2, 0, 10);

        verify(derivados).grupos(GruposKey.de(42, "nike", "remera", "", true));
    }

    @Test
    void noSnapshotIsNoContentWithoutTouchingTheCache() {
        when(service.getLastResult()).thenReturn(null);

        var respuesta = endpoints.grupos("", "", "", "", 1, 0, 10);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(204);
        Mockito.verifyNoInteractions(derivados);
    }

    @Test
    void aSnapshotClearedBetweenTheCheckAndTheComputeIsAnEmptyPage() {
        when(derivados.grupos(Mockito.any())).thenReturn(List.of());

        var respuesta = endpoints.grupos("", "", "", "", 1, 0, 10);

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        assertThat(respuesta.getBody().getData()).isEmpty();
    }
}
