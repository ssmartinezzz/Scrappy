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
@DisplayName("Samsung: the curated leaf outranks the name, except where the name says another device. Names are real rows (2026-11-19)")
class SamsungHojaGanaTest {

    private static final int TV = 1;
    private static final int PARTES = 2;
    private static final int SERIE_S = 3;
    private static final int HELADERA = 4;

    private final NormalizerService normalizer = normalizer();

    private static NormalizerService normalizer() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "samsung", new SiteRegistry.Sitio("Samsung", "samsung", "vtex", false, null, "db", true, "tecnologia"),
                "otra", new SiteRegistry.Sitio("Otra", "otra", "vtex", false, null, "db", false, "tecnologia")));
        SitioCategoriaPort arbol = mock(SitioCategoriaPort.class);
        Map<Integer, String> hojas = Map.of(TV, "TV y Audio", PARTES, "Otros", SERIE_S, "Celular", HELADERA, "Heladera");
        when(arbol.categoriaPorOrigen("Samsung")).thenReturn(hojas);
        when(arbol.categoriaPorOrigen("Otra")).thenReturn(hojas);
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(), TestRubros.resolver(registry),
                new GymratTagger(), new SportResolver(), registry, arbol);
    }

    private Product normalizar(String sitio, String nombre, int hoja) {
        Product p = Product.builder().sitio(sitio).nombre(nombre).url("https://x/" + sitio + nombre)
                .sitioCategoriaOrigenId(hoja).build();
        return normalizer.normalizar(List.of(p)).get(0);
    }

    @Test
    @DisplayName("a Mini LED TV is a TV, not a bag, and a fridge drawer is not an office organizer")
    void leafBeatsAMisleadingName() {
        Product tv = normalizar("Samsung", "85\" Mini LED M80H 4K Samsung Vision AI Smart TV", TV);
        assertThat(tv.categoria()).isEqualTo("TV y Audio");
        assertThat(tv.rubro()).as("V139: a Televisor is an appliance on a store with no forced rubro").isEqualTo("electrodomesticos");
        assertThat(normalizar("Samsung", "Cajón Inferior de Freezer", PARTES).categoria()).isEqualTo("Otros");
        Product heladera = normalizar("Samsung", "Freezer Superior Heladera Twin Cooling Plus 382L", HELADERA);
        assertThat(heladera.categoria()).isEqualTo("Heladera");
        assertThat(heladera.rubro()).isEqualTo("electrodomesticos");
    }

    @Test
    @DisplayName("a Galaxy Tab on a phone series leaf stays a Tablet")
    void otherDevicesWin() {
        assertThat(normalizar("Samsung", "Galaxy Tab S12 Ultra Gray 256GB - Incluye funda con teclado + S Pen", SERIE_S).categoria())
                .isEqualTo("Tablet");
    }

    @Test
    @DisplayName("Prestigio is curated too: a Procesador 400 W under Procesadoras y Mixer is a kitchen appliance, not a CPU")
    void prestigioLeafBeatsTheCpuName() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "prestigio", new SiteRegistry.Sitio("Prestigio", "prestigio", "vtex", false, null, "db", false, "construccion")));
        SitioCategoriaPort arbol = mock(SitioCategoriaPort.class);
        when(arbol.categoriaPorOrigen("Prestigio")).thenReturn(Map.of(9, "Pequeño Electrodoméstico"));
        NormalizerService prestigio = new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(), TestRubros.resolver(registry),
                new GymratTagger(), new SportResolver(), registry, arbol);
        Product p = Product.builder().sitio("Prestigio").nombre("Procesador 400 W AM115").url("https://x/p").sitioCategoriaOrigenId(9).build();
        Product n = prestigio.normalizar(List.of(p)).get(0);
        assertThat(n.categoria()).isEqualTo("Pequeño Electrodoméstico");
        assertThat(n.rubro()).isEqualTo("electrodomesticos");
    }

    @Test
    @DisplayName("other stores keep the name first")
    void otherStoresKeepNameFirst() {
        assertThat(normalizar("Otra", "Cajón Inferior de Freezer", PARTES).categoria()).isEqualTo("Organización");
    }
}
