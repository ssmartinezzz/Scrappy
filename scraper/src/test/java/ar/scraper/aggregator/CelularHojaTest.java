package ar.scraper.aggregator;

import ar.scraper.testsupport.TestRubros;

import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.GymratTagger;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.aggregator.normalize.SportResolver;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.catalog.SitioCategoriaPort;
import ar.scraper.classification.BrandExtractor;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Epic("Normalization")
@Feature("Category")
@DisplayName("A curated Celular leaf outranks the name, except when the name says another device")
class CelularHojaTest {

    private static final int HOJA_CELULAR = 7;

    private final NormalizerService normalizer = normalizer();

    private static NormalizerService normalizer() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "samsung", new SiteRegistry.Sitio("Samsung", "samsung", "vtex", false, null, "db", true, "tecnologia")));
        SitioCategoriaPort arbol = mock(SitioCategoriaPort.class);
        when(arbol.categoriaPorOrigen("Samsung")).thenReturn(Map.of(HOJA_CELULAR, "Celular"));
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(), TestRubros.resolver(registry),
                new GymratTagger(), new SportResolver(), registry, arbol);
    }

    private Product normalizar(String nombre) {
        Product p = Product.builder().sitio("Samsung").nombre(nombre).url("https://x/" + nombre)
                .sitioCategoriaOrigenId(HOJA_CELULAR).build();
        return normalizer.normalizar(List.of(p)).get(0);
    }

    @Test
    @DisplayName("an accessory the name does not tie to a phone is Celular because of its leaf, with a sub from the name")
    void leafMakesItCelular() {
        Product cable = normalizar("Cable de datos C to C 1.8m | Max 5A| 100W");
        assertThat(cable.categoria()).isEqualTo("Celular");
        assertThat(cable.subCategoria()).isEqualTo("Cable");
        assertThat(cable.rubro()).isEqualTo("celulares");
        assertThat(normalizar("Smart Tag").categoria()).isEqualTo("Celular");
        assertThat(normalizar("Funda Slim Magnética").subCategoria()).isEqualTo("Funda");
    }

    @Test
    @DisplayName("a Galaxy Tab or Watch on a phone leaf stays a Tablet or Reloj")
    void otherDevicesWin() {
        assertThat(normalizar("Galaxy Tab S12 Ultra Gray 256GB - Incluye funda con teclado + S Pen").categoria()).isEqualTo("Tablet");
        assertThat(normalizar("Galaxy Watch 9 Series 44mm").categoria()).isEqualTo("Reloj");
    }
}
