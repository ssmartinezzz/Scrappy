package ar.scraper.ml;

import ar.scraper.catalog.HistorialEntry;
import ar.scraper.catalog.HistorialPort;
import ar.scraper.indices.Confianza;
import ar.scraper.indices.Deflactor;
import ar.scraper.indices.IndiceService;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SenalEnricher} orchestration logic: batch historial
 * loading + resolving a date-range deflator per product before delegating to
 * the pure {@link SenalCalculator#compute}. {@link HistorialPort} and
 * {@link IndiceService} are mocked since SenalEnricher's only job is to wire
 * batch-fetched data into the pure calculator — the calculator's branch logic
 * is already covered by {@link SenalCalculatorTest}.
 */
@Epic("ML Pipeline")
@Feature("Señales de Compra")
@Story("Enricher")
@DisplayName("SenalEnricher — batch historial loading, date/rubro-driven deflator, per-product classification")
class SenalEnricherTest {

    @Step("Build product fixture for url {url}")
    private Product producto(String url) {
        return new Product("Sitio", "Producto " + url, 1000.0, null, url,
                "", "Remera", "unisex", List.of());
    }

    @Test
    void productWithPopulatedHistorialGetsClassifiedSenal() {
        HistorialPort historial = Mockito.mock(HistorialPort.class);
        IndiceService indices = Mockito.mock(IndiceService.class);

        List<HistorialEntry> historialEntries = List.of(
                new HistorialEntry("2026-01-01", 1000.0),
                new HistorialEntry("2026-02-01", 1200.0),
                new HistorialEntry("2026-03-01", 800.0) // current = historical min -> comprar_ahora
        );
        when(historial.getHistorialPrecios(anyList())).thenReturn(
                Map.of("https://site.com/p1", historialEntries));
        when(indices.deflactorParaRubro(any(), any(), any())).thenReturn(Deflactor.NEUTRO);

        SenalEnricher enricher = new SenalEnricher(historial, indices);
        List<Product> result = enricher.enriquecer(List.of(producto("https://site.com/p1")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).senal().senal()).isEqualTo("comprar_ahora");
        assertThat(result.get(0).senal().scoreCompra()).isEqualTo(95);
        assertThat(result.get(0).senal().confianzaDeflactor()).isEqualTo(Confianza.SIN_DATOS);
    }

    @Test
    void resolvesDeflactorFromHistorialDatesNotFromPointCount() {
        // Four points inside one week, one anchor 9 months earlier — COUNT-based
        // ("13 points back") would pick the wrong anchor; DATE must pick the
        // oldest and newest points regardless of how many sit in between.
        HistorialPort historial = Mockito.mock(HistorialPort.class);
        IndiceService indices = Mockito.mock(IndiceService.class);

        List<HistorialEntry> historialEntries = List.of(
                new HistorialEntry("2025-06-01", 400000.0),
                new HistorialEntry("2025-06-05", 400000.0),
                new HistorialEntry("2025-06-10", 400000.0),
                new HistorialEntry("2026-03-01", 500000.0));
        when(historial.getHistorialPrecios(anyList())).thenReturn(
                Map.of("https://site.com/gpu", historialEntries));
        when(indices.deflactorParaRubro(any(), any(), any())).thenReturn(Deflactor.NEUTRO);

        Product gpu = new Product("Sitio", "GPU", 500000.0, null, "https://site.com/gpu", "",
                "GPU", "unisex", List.of(), Product.MlScore.EMPTY, "", "tecnologia", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1);

        SenalEnricher enricher = new SenalEnricher(historial, indices);
        enricher.enriquecer(List.of(gpu));

        Mockito.verify(indices).deflactorParaRubro(
                "tecnologia", LocalDate.parse("2025-06-01"), LocalDate.parse("2026-03-01"));
    }

    @Test
    void productWithEmptyHistorialGetsSinDatosWithoutResolvingADeflactor() {
        HistorialPort historial = Mockito.mock(HistorialPort.class);
        IndiceService indices = Mockito.mock(IndiceService.class);

        // Batch map does not contain this product's URL at all (no historial rows).
        when(historial.getHistorialPrecios(anyList())).thenReturn(Map.of());

        SenalEnricher enricher = new SenalEnricher(historial, indices);
        List<Product> result = enricher.enriquecer(List.of(producto("https://site.com/p2")));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).senal().senal()).isEqualTo("sin_datos");
        assertThat(result.get(0).senal().scoreCompra()).isEqualTo(50);
        Mockito.verify(indices, Mockito.never()).deflactorParaRubro(any(), any(), any());
    }

    @Test
    void batchLoadsHistorialOnceForAllProductsNotPerProduct() {
        // Proves no N+1: getHistorialPrecios(List) is called exactly once
        // regardless of how many products are enriched.
        HistorialPort historial = Mockito.mock(HistorialPort.class);
        IndiceService indices = Mockito.mock(IndiceService.class);

        when(historial.getHistorialPrecios(anyList())).thenReturn(Map.of());

        SenalEnricher enricher = new SenalEnricher(historial, indices);
        enricher.enriquecer(List.of(
                producto("https://site.com/p3"),
                producto("https://site.com/p4"),
                producto("https://site.com/p5")));

        Mockito.verify(historial, Mockito.times(1)).getHistorialPrecios(anyList());
    }

    @Test
    void productsWithoutUrlAreSkippedFromBatchLookupButStillReturned() {
        HistorialPort historial = Mockito.mock(HistorialPort.class);
        IndiceService indices = Mockito.mock(IndiceService.class);

        when(historial.getHistorialPrecios(anyList())).thenReturn(Map.of());

        SenalEnricher enricher = new SenalEnricher(historial, indices);
        Product sinUrl = new Product("Sitio", "Sin URL", 500.0, null, "",
                "", "Remera", "unisex", List.of());

        List<Product> result = enricher.enriquecer(List.of(sinUrl));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).senal()).isEqualTo(Product.SenalCompra.EMPTY);
    }

    @Test
    void packProductPreservesCantidadUnidadesAfterEnrichment() {
        // Regression for PR2: withSenal() previously rebuilt Product via the
        // 16-arg legacy constructor, silently resetting cantidadUnidades to 1.
        HistorialPort historial = Mockito.mock(HistorialPort.class);
        IndiceService indices = Mockito.mock(IndiceService.class);

        when(historial.getHistorialPrecios(anyList())).thenReturn(Map.of());

        Product pack = new Product(
                "Sitio", "Pack x3 Remeras", 15000.0, null, "https://site.com/pack",
                "", "Remera", "unisex", List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 3);

        SenalEnricher enricher = new SenalEnricher(historial, indices);
        List<Product> result = enricher.enriquecer(List.of(pack));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).cantidadUnidades()).isEqualTo(3);
        assertThat(result.get(0).esPack()).isTrue();
    }

    @Test
    void productPreservesVisualAttrsAfterEnrichment() {
        // Regression for fashion-image-classification PR1: withSenal() previously
        // rebuilt Product via the 18-arg legacy constructor, silently resetting
        // visual to VisualAttrs.EMPTY.
        HistorialPort historial = Mockito.mock(HistorialPort.class);
        IndiceService indices = Mockito.mock(IndiceService.class);

        when(historial.getHistorialPrecios(anyList())).thenReturn(Map.of());

        Product.VisualAttrs visual = new Product.VisualAttrs("oversize", "estampado", "cuello redondo", "azul");
        Product conVisual = new Product(
                "Sitio", "Remera con visual", 15000.0, null, "https://site.com/visual",
                "", "Remera", "unisex", List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1, "", visual);

        SenalEnricher enricher = new SenalEnricher(historial, indices);
        List<Product> result = enricher.enriquecer(List.of(conVisual));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).visual()).isEqualTo(visual);
    }
}
