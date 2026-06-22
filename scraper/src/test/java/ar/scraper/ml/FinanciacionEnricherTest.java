package ar.scraper.ml;

import ar.scraper.db.DatabaseService;
import ar.scraper.db.DatabaseService.Preset;
import ar.scraper.model.Product;
import ar.scraper.web.InflacionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FinanciacionEnricher} orchestration logic: reads the
 * active preset + inflation once, then delegates per-product math to the
 * pure {@link FinanciacionCalculator}. {@link DatabaseService} and
 * {@link InflacionService} are mocked since the enricher's only job is
 * wiring — calculator branch logic is already covered by
 * {@link FinanciacionCalculatorTest}.
 */
class FinanciacionEnricherTest {

    private Product producto(String nombre, double precio) {
        return new Product("Sitio", nombre, precio, null, "https://site.com/" + nombre,
                "", "Remeras", "unisex", List.of());
    }

    @Test
    void productsGetFinancingSignalWhenPresetIsActive() {
        DatabaseService db = Mockito.mock(DatabaseService.class);
        InflacionService inflacion = Mockito.mock(InflacionService.class);

        Preset preset = new Preset(1, "12 cuotas / 40% recargo", 40.0, 12, true);
        when(db.cargarPresetActivo()).thenReturn(Optional.of(preset));
        when(inflacion.getInflacionMensual()).thenReturn(3.5);

        FinanciacionEnricher enricher = new FinanciacionEnricher(db, inflacion);
        List<Product> result = enricher.enriquecer(List.of(producto("p1", 100000)));

        assertThat(result).hasSize(1);
        Product.SenalFinanciacion finan = result.get(0).finan();
        assertThat(finan.senal()).isNotEqualTo("sin_datos");
        assertThat(finan.senal()).isNotEqualTo("sin_preset_activo");
        assertThat(finan.cuotas()).isEqualTo(12);
        assertThat(finan.recargoPct()).isEqualTo(40.0);
    }

    @Test
    void productsFallBackToSinPresetActivoWhenNoActivePreset() {
        DatabaseService db = Mockito.mock(DatabaseService.class);
        InflacionService inflacion = Mockito.mock(InflacionService.class);

        when(db.cargarPresetActivo()).thenReturn(Optional.empty());

        FinanciacionEnricher enricher = new FinanciacionEnricher(db, inflacion);
        List<Product> result = enricher.enriquecer(List.of(producto("p2", 50000)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).finan().senal()).isEqualTo("sin_preset_activo");
        Mockito.verify(inflacion, Mockito.never()).getInflacionMensual();
    }

    @Test
    void readsActivePresetAndInflationExactlyOnceRegardlessOfProductCount() {
        DatabaseService db = Mockito.mock(DatabaseService.class);
        InflacionService inflacion = Mockito.mock(InflacionService.class);

        Preset preset = new Preset(2, "Otro preset", 25.0, 6, true);
        when(db.cargarPresetActivo()).thenReturn(Optional.of(preset));
        when(inflacion.getInflacionMensual()).thenReturn(2.0);

        FinanciacionEnricher enricher = new FinanciacionEnricher(db, inflacion);
        enricher.enriquecer(List.of(
                producto("p3", 10000),
                producto("p4", 20000),
                producto("p5", 30000)));

        Mockito.verify(db, Mockito.times(1)).cargarPresetActivo();
        Mockito.verify(inflacion, Mockito.times(1)).getInflacionMensual();
    }

    @Test
    void preservesExistingSenalCompraFieldUnchanged() {
        DatabaseService db = Mockito.mock(DatabaseService.class);
        InflacionService inflacion = Mockito.mock(InflacionService.class);

        Preset preset = new Preset(3, "Preset", 40.0, 12, true);
        when(db.cargarPresetActivo()).thenReturn(Optional.of(preset));
        when(inflacion.getInflacionMensual()).thenReturn(3.5);

        Product.SenalCompra senalOriginal = new Product.SenalCompra("comprar_ahora", 95);
        Product withSenal = new Product(
                "Sitio", "p6", 100000, null, "https://site.com/p6", "",
                "Remeras", "unisex", List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, senalOriginal, Product.SenalFinanciacion.EMPTY);

        FinanciacionEnricher enricher = new FinanciacionEnricher(db, inflacion);
        List<Product> result = enricher.enriquecer(List.of(withSenal));

        assertThat(result.get(0).senal()).isEqualTo(senalOriginal);
        assertThat(result.get(0).finan()).isNotEqualTo(Product.SenalFinanciacion.EMPTY);
    }

    @Test
    void emptyOrNullListIsReturnedAsIs() {
        DatabaseService db = Mockito.mock(DatabaseService.class);
        InflacionService inflacion = Mockito.mock(InflacionService.class);
        FinanciacionEnricher enricher = new FinanciacionEnricher(db, inflacion);

        assertThat(enricher.enriquecer(List.of())).isEmpty();
        assertThat(enricher.enriquecer(null)).isNull();
        Mockito.verify(db, Mockito.never()).cargarPresetActivo();
    }

    @Test
    void packProductPreservesCantidadUnidadesAfterEnrichment() {
        // Regression for PR2: withFinan() previously rebuilt Product via the
        // 16-arg legacy constructor, silently resetting cantidadUnidades to 1.
        DatabaseService db = Mockito.mock(DatabaseService.class);
        InflacionService inflacion = Mockito.mock(InflacionService.class);

        Preset preset = new Preset(4, "Preset", 40.0, 12, true);
        when(db.cargarPresetActivo()).thenReturn(Optional.of(preset));
        when(inflacion.getInflacionMensual()).thenReturn(3.5);

        Product pack = new Product(
                "Sitio", "Combo x2 Buzo + Pantalon", 100000.0, null, "https://site.com/combo",
                "", "Conjunto", "unisex", List.of(), Product.MlScore.EMPTY, "", "indumentaria",
                false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 2);

        FinanciacionEnricher enricher = new FinanciacionEnricher(db, inflacion);
        List<Product> result = enricher.enriquecer(List.of(pack));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).cantidadUnidades()).isEqualTo(2);
        assertThat(result.get(0).esPack()).isTrue();
    }
}
