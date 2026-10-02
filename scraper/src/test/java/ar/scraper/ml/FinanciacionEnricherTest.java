package ar.scraper.ml;

import ar.scraper.financiacion.Preset;
import ar.scraper.financiacion.PresetPort;
import ar.scraper.model.Product;
import ar.scraper.indices.Indice;
import ar.scraper.indices.IndiceService;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FinanciacionEnricher} orchestration logic: reads the
 * active preset + IPC monthly variation once, then delegates per-product math
 * to the pure {@link FinanciacionCalculator}. {@link PresetPort} and
 * {@link IndiceService} are mocked since the enricher's only job is
 * wiring — calculator branch logic is already covered by
 * {@link FinanciacionCalculatorTest}.
 */
@Epic("ML Pipeline")
@Feature("Financiación")
@Story("Enricher")
@DisplayName("FinanciacionEnricher — preset + inflation wiring into per-product signals")
class FinanciacionEnricherTest {

    @Step("Build product fixture: {nombre} @ {precio}")
    private Product producto(String nombre, double precio) {
        return Product.builder()
                .sitio("Sitio")
                .nombre(nombre)
                .precio(precio)
                .precioOriginal(null)
                .url("https://site.com/" + nombre)
                .imagenUrl("")
                .categoria("Remera")
                .genero("unisex")
                .talles(List.of())
                .build();
    }

    @Test
    void productsGetFinancingSignalWhenPresetIsActive() {
        PresetPort presets = Mockito.mock(PresetPort.class);
        IndiceService indices = Mockito.mock(IndiceService.class);

        Preset preset = new Preset(1, "12 cuotas / 40% recargo", 40.0, 12, true);
        when(presets.cargarPresetActivo()).thenReturn(Optional.of(preset));
        when(indices.variacionMensual(Indice.IPC)).thenReturn(Optional.of(3.5));

        FinanciacionEnricher enricher = new FinanciacionEnricher(presets, indices);
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
        PresetPort presets = Mockito.mock(PresetPort.class);
        IndiceService indices = Mockito.mock(IndiceService.class);

        when(presets.cargarPresetActivo()).thenReturn(Optional.empty());

        FinanciacionEnricher enricher = new FinanciacionEnricher(presets, indices);
        List<Product> result = enricher.enriquecer(List.of(producto("p2", 50000)));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).finan().senal()).isEqualTo("sin_preset_activo");
        Mockito.verify(indices, Mockito.never()).variacionMensual(Indice.IPC);
    }

    @Test
    void readsActivePresetAndInflationExactlyOnceRegardlessOfProductCount() {
        PresetPort presets = Mockito.mock(PresetPort.class);
        IndiceService indices = Mockito.mock(IndiceService.class);

        Preset preset = new Preset(2, "Otro preset", 25.0, 6, true);
        when(presets.cargarPresetActivo()).thenReturn(Optional.of(preset));
        when(indices.variacionMensual(Indice.IPC)).thenReturn(Optional.of(2.0));

        FinanciacionEnricher enricher = new FinanciacionEnricher(presets, indices);
        enricher.enriquecer(List.of(
                producto("p3", 10000),
                producto("p4", 20000),
                producto("p5", 30000)));

        Mockito.verify(presets, Mockito.times(1)).cargarPresetActivo();
        Mockito.verify(indices, Mockito.times(1)).variacionMensual(Indice.IPC);
    }

    @Test
    void preservesExistingSenalCompraFieldUnchanged() {
        PresetPort presets = Mockito.mock(PresetPort.class);
        IndiceService indices = Mockito.mock(IndiceService.class);

        Preset preset = new Preset(3, "Preset", 40.0, 12, true);
        when(presets.cargarPresetActivo()).thenReturn(Optional.of(preset));
        when(indices.variacionMensual(Indice.IPC)).thenReturn(Optional.of(3.5));

        Product.SenalCompra senalOriginal =
                new Product.SenalCompra("comprar_ahora", 95, ar.scraper.indices.Confianza.OBSERVADO);
        Product withSenal = Product.builder()
                .sitio("Sitio")
                .nombre("p6")
                .precio(100000)
                .precioOriginal(null)
                .url("https://site.com/p6")
                .imagenUrl("")
                .categoria("Remera")
                .genero("unisex")
                .talles(List.of())
                .ml(Product.MlScore.EMPTY)
                .marca("")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(senalOriginal)
                .finan(Product.SenalFinanciacion.EMPTY)
                .build();

        FinanciacionEnricher enricher = new FinanciacionEnricher(presets, indices);
        List<Product> result = enricher.enriquecer(List.of(withSenal));

        assertThat(result.get(0).senal()).isEqualTo(senalOriginal);
        assertThat(result.get(0).finan()).isNotEqualTo(Product.SenalFinanciacion.EMPTY);
    }

    @Test
    void emptyOrNullListIsReturnedAsIs() {
        PresetPort presets = Mockito.mock(PresetPort.class);
        IndiceService indices = Mockito.mock(IndiceService.class);
        FinanciacionEnricher enricher = new FinanciacionEnricher(presets, indices);

        assertThat(enricher.enriquecer(List.of())).isEmpty();
        assertThat(enricher.enriquecer(null)).isNull();
        Mockito.verify(presets, Mockito.never()).cargarPresetActivo();
    }

    @Test
    void packProductPreservesCantidadUnidadesAfterEnrichment() {
        // Regression for PR2: withFinan() previously rebuilt Product via the
        // 16-arg legacy constructor, silently resetting cantidadUnidades to 1.
        PresetPort presets = Mockito.mock(PresetPort.class);
        IndiceService indices = Mockito.mock(IndiceService.class);

        Preset preset = new Preset(4, "Preset", 40.0, 12, true);
        when(presets.cargarPresetActivo()).thenReturn(Optional.of(preset));
        when(indices.variacionMensual(Indice.IPC)).thenReturn(Optional.of(3.5));

        Product pack = Product.builder()
                .sitio("Sitio")
                .nombre("Combo x2 Buzo + Pantalon")
                .precio(100000.0)
                .precioOriginal(null)
                .url("https://site.com/combo")
                .imagenUrl("")
                .categoria("Conjunto")
                .genero("unisex")
                .talles(List.of())
                .ml(Product.MlScore.EMPTY)
                .marca("")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(2)
                .build();

        FinanciacionEnricher enricher = new FinanciacionEnricher(presets, indices);
        List<Product> result = enricher.enriquecer(List.of(pack));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).cantidadUnidades()).isEqualTo(2);
        assertThat(result.get(0).esPack()).isTrue();
    }

    @Test
    void productPreservesVisualAttrsAfterEnrichment() {
        // Regression for fashion-image-classification PR1: withFinan() previously
        // rebuilt Product via the 18-arg legacy constructor, silently resetting
        // visual to VisualAttrs.EMPTY.
        PresetPort presets = Mockito.mock(PresetPort.class);
        IndiceService indices = Mockito.mock(IndiceService.class);

        Preset preset = new Preset(5, "Preset", 40.0, 12, true);
        when(presets.cargarPresetActivo()).thenReturn(Optional.of(preset));
        when(indices.variacionMensual(Indice.IPC)).thenReturn(Optional.of(3.5));

        Product.VisualAttrs visual = new Product.VisualAttrs("entallado", "liso", "en v", "negro");
        Product conVisual = Product.builder()
                .sitio("Sitio")
                .nombre("Remera con visual")
                .precio(100000.0)
                .precioOriginal(null)
                .url("https://site.com/visual-finan")
                .imagenUrl("")
                .categoria("Remera")
                .genero("unisex")
                .talles(List.of())
                .ml(Product.MlScore.EMPTY)
                .marca("")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1)
                .subCategoria("")
                .visual(visual)
                .build();

        FinanciacionEnricher enricher = new FinanciacionEnricher(presets, indices);
        List<Product> result = enricher.enriquecer(List.of(conVisual));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).visual()).isEqualTo(visual);
    }
}
