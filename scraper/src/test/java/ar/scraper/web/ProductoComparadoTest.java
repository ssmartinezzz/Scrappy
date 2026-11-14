package ar.scraper.web;

import ar.scraper.aggregator.grouping.ProductGroup;
import ar.scraper.catalog.HistorialEntry;
import ar.scraper.catalog.HistorialPort;
import ar.scraper.catalog.ProductKey;
import ar.scraper.catalog.ProductPort;
import ar.scraper.model.Product;
import ar.scraper.web.cache.CatalogoDerivadoCache;
import ar.scraper.web.cache.CatalogoDerivadoCache.GruposKey;
import ar.scraper.web.support.Wire;
import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/producto/{key}/comparado}: the same article in the other stores, with each
 * store's price history, so the history page can draw one line per store. "Same article" is the
 * cached grouping; the product itself is read from the DB like {@code /api/producto/{key}}.
 */
@Epic("REST API")
@Feature("Catálogo")
@DisplayName("ProductoComparadoController — GET /api/producto/{key}/comparado")
class ProductoComparadoTest {

    private static final String URL_A = "https://a.com/zapatilla";
    private static final String URL_B = "https://b.com/zapatilla";
    private static final String URL_C = "https://c.com/zapatilla";

    private ScraperService service;
    private CatalogoDerivadoCache derivados;
    private ProductPort productos;
    private HistorialPort historial;
    private ProductoComparadoController controller;

    @BeforeEach
    void setUp() {
        service = mock(ScraperService.class);
        derivados = mock(CatalogoDerivadoCache.class);
        productos = mock(ProductPort.class);
        historial = mock(HistorialPort.class);
        when(service.snapshotVersion()).thenReturn(7L);
        controller = new ProductoComparadoController(service, derivados, productos, historial,
                ar.scraper.catalog.PoliticaStock.porDefecto());
    }

    private static Product producto(String sitio, String url, double precio) {
        return Product.builder()
                .sitio(sitio).nombre("Zapatilla Run").precio(precio).url(url)
                .imagenUrl("https://img/" + sitio + ".jpg").categoria("Zapatilla")
                .genero("unisex").talles(List.of("42")).ml(Product.MlScore.EMPTY).marca("Nike")
                .rubro("indumentaria").senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).build();
    }

    private void grupos(Product... miembros) {
        when(derivados.grupos(GruposKey.de(7L, null, null, null, true)))
                .thenReturn(List.of(new ProductGroup(List.of(miembros))));
    }

    private void existe(String url) {
        when(productos.obtenerProductoPorKey(ProductKey.of(url)))
                .thenReturn(Optional.of(producto("X", url, 1)));
    }

    @Test
    @DisplayName("returns the other members of the group, each with its price history, one bulk read")
    void returnsSiblingsWithHistoryExcludingSelf() {
        existe(URL_A);
        grupos(producto("A", URL_A, 100), producto("B", URL_B, 90), producto("C", URL_C, 120));
        when(historial.getHistorialPrecios(List.of(URL_B, URL_C))).thenReturn(Map.of(
                URL_B, List.of(new HistorialEntry("2026-11-01", 95), new HistorialEntry("2026-11-10", 90)),
                URL_C, List.of(new HistorialEntry("2026-11-10", 120))));

        JsonNode data = Wire.data(controller.comparado(ProductKey.of(URL_A)));

        assertThat(data.isArray()).isTrue();
        assertThat(data).hasSize(2);
        JsonNode b = data.get(0);
        assertThat(b.path("sitio").asText()).isEqualTo("B");
        assertThat(b.path("url").asText()).isEqualTo(URL_B);
        assertThat(b.path("key").asText()).isEqualTo(ProductKey.of(URL_B));
        assertThat(b.path("precio").asDouble()).isEqualTo(90);
        assertThat(b.path("img").asText()).isEqualTo("https://img/B.jpg");
        assertThat(b.path("stock").asText()).isNotBlank();
        assertThat(b.path("historial").path("puntos")).hasSize(2);
        assertThat(b.path("historial").path("puntos").get(0).path("fecha").asText()).isEqualTo("2026-11-01");
        assertThat(b.path("historial").path("deltaPct").asDouble()).isEqualTo(-5.3);
        assertThat(data.get(1).path("sitio").asText()).isEqualTo("C");
        verify(historial, never()).cargarHistorial(anyString());
    }

    @Test
    @DisplayName("a sibling with no recorded history still comes back, with an empty series")
    void siblingWithoutHistoryHasEmptyPoints() {
        existe(URL_A);
        grupos(producto("A", URL_A, 100), producto("B", URL_B, 90));
        when(historial.getHistorialPrecios(List.of(URL_B))).thenReturn(Map.of());

        JsonNode data = Wire.data(controller.comparado(ProductKey.of(URL_A)));

        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("historial").path("puntos")).isEmpty();
    }

    @Test
    @DisplayName("a product that is not in any multi-site group has no siblings and costs no history read")
    void aloneIsEmpty() {
        existe(URL_A);
        grupos(producto("B", URL_B, 90), producto("C", URL_C, 120));

        JsonNode data = Wire.data(controller.comparado(ProductKey.of(URL_A)));

        assertThat(data.isArray()).isTrue();
        assertThat(data).isEmpty();
        verify(historial, never()).getHistorialPrecios(org.mockito.ArgumentMatchers.<List<String>>any());
    }

    @Test
    @DisplayName("a product in the DB but not in the snapshot (soft-deleted) has no siblings")
    void notInSnapshotIsEmpty() {
        existe(URL_A);
        when(derivados.grupos(GruposKey.de(7L, null, null, null, true))).thenReturn(List.of());

        assertThat(Wire.data(controller.comparado(ProductKey.of(URL_A)))).isEmpty();
    }

    @Test
    @DisplayName("an unknown key is a 404")
    void unknownKeyIs404() {
        when(productos.obtenerProductoPorKey("nope")).thenReturn(Optional.empty());

        var resp = Wire.answer(() -> controller.comparado("nope"));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verify(derivados, never()).grupos(org.mockito.ArgumentMatchers.any());
    }
}
