package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.aggregator.grouping.GroupingService;
import ar.scraper.aggregator.grouping.JaccardSimilarity;
import ar.scraper.aggregator.grouping.ProductIdentity;
import ar.scraper.catalog.PoliticaStock;
import ar.scraper.catalog.PreciosExternosPort;
import ar.scraper.model.Product;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.support.Wire;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ComparadorController — /api/grupos stock por oferta")
class ComparadorStockTest {

    private static final Instant AHORA = Instant.parse("2026-11-13T12:00:00Z");

    private ScraperService service;
    private ComparadorController endpoints;

    @BeforeEach
    void setUp() {
        service = mock(ScraperService.class);
        PoliticaStock politica = new PoliticaStock(Clock.fixed(AHORA, ZoneOffset.UTC), Duration.ofDays(7));
        endpoints = new ComparadorController(service, mock(PreciosExternosPort.class),
                new CatalogoDerivadoCache(service, new GroupingService(new ProductIdentity(), new JaccardSimilarity())),
                politica);
    }

    private static Product oferta(String sitio, String nombre, double precio, Boolean enStock) {
        return Product.builder().sitio(sitio).nombre(nombre).precio(precio).url("https://" + sitio + "/x")
                .imagenUrl("img").categoria("Almacenamiento").genero("unisex").talles(List.of()).marca("Kingston")
                .rubro("tecnologia").enStock(enStock)
                .stockChequeadoAt(enStock == null ? null : AHORA.minus(Duration.ofHours(3)))
                .vistoEnStockAt(Boolean.TRUE.equals(enStock) ? AHORA.minus(Duration.ofHours(3)) : null).build();
    }

    private void publicar(Product... productos) {
        List<Product> lista = new java.util.ArrayList<>(List.of(productos));
        lista.sort(Comparator.comparingDouble(Product::precio));
        when(service.getLastResult()).thenReturn(new AggregatedResult(List.copyOf(lista), Map.of(), Map.of(),
                ResultAggregator.calcularFacets(lista), lista.get(0).precio(), lista.get(lista.size() - 1).precio()));
    }

    private JsonNode precios(String stock) {
        JsonNode grupos = Wire.body(endpoints.grupos(null, null, null, null, 1, 0, 20, stock)).path("data");
        assertThat(grupos).hasSize(1);
        return grupos.get(0).path("precios");
    }

    @Test
    @DisplayName("each offer carries its derived stock and timestamps")
    void offersCarryStock() {
        publicar(oferta("a", "SSD Kingston NV2 1TB", 100, false), oferta("b", "SSD Kingston NV2 1TB", 120, true),
                oferta("c", "SSD Kingston NV2 1TB", 130, null));

        JsonNode p = precios(null);

        assertThat(p).hasSize(3);
        assertThat(p.get(0).path("stock").asText()).isEqualTo("sin_stock");
        assertThat(p.get(1).path("stock").asText()).isEqualTo("en_stock");
        assertThat(p.get(1).path("vistoEnStockAt").asText()).isEqualTo("2026-11-13T09:00:00Z");
        assertThat(p.get(2).path("stock").asText()).isEqualTo("desconocido");
    }

    @Test
    @DisplayName("excluir_sin_stock removes the sold-out offer and the group's cheapest price follows")
    void excludesSoldOutOffers() {
        publicar(oferta("a", "SSD Kingston NV2 1TB", 100, false), oferta("b", "SSD Kingston NV2 1TB", 120, true),
                oferta("c", "SSD Kingston NV2 1TB", 130, null));

        JsonNode grupos = Wire.body(endpoints.grupos(null, null, null, null, 1, 0, 20, "excluir_sin_stock")).path("data");

        assertThat(grupos.get(0).path("precios")).hasSize(2);
        assertThat(grupos.get(0).path("precioMin").asDouble()).isEqualTo(120.0);
    }

    @Test
    @DisplayName("a multi-site group left with one site no longer qualifies when minSitios >= 2")
    void groupBelowMinSitiosDisappears() {
        publicar(oferta("a", "SSD Kingston NV2 1TB", 100, false), oferta("b", "SSD Kingston NV2 1TB", 120, true));

        var resp = endpoints.grupos(null, null, null, null, 2, 0, 20, "excluir_sin_stock");

        assertThat(Wire.body(resp).path("data")).isEmpty();
    }
}
