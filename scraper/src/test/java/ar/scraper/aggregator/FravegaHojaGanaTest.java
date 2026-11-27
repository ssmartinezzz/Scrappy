package ar.scraper.aggregator;

import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.GymratTagger;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.aggregator.normalize.SportResolver;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.catalog.SitioCategoriaPort;
import ar.scraper.classification.BrandExtractor;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.model.Product;
import ar.scraper.testsupport.TestRubros;
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
@Feature("Site leaves")
@DisplayName("V145: on Fravega the curated leaf outranks the name and fills the sub of an abbreviated name")
class FravegaHojaGanaTest {

    private static final int FREEZERS = 1000183;
    private static final int PEQUENOS = 2;

    private final NormalizerService normalizer = normalizer();

    private static NormalizerService normalizer() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "fravega", new SiteRegistry.Sitio("Fravega", "fravega", "vtex", false, null, "db", false, "electrodomesticos"),
                "easy", new SiteRegistry.Sitio("Easy", "easy", "vtex", false, null, "db", false, null)));
        SitioCategoriaPort arbol = mock(SitioCategoriaPort.class);
        for (String sitio : List.of("Fravega", "Easy")) {
            when(arbol.categoriaPorOrigen(sitio)).thenReturn(Map.of(FREEZERS, "Heladera", PEQUENOS, "Pequeño Electrodoméstico"));
            when(arbol.subCategoriaPorOrigen(sitio)).thenReturn(Map.of(FREEZERS, "Freezer"));
        }
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(), TestRubros.resolver(registry),
                new GymratTagger(), new SportResolver(), registry, arbol);
    }

    private Product normalizar(String nombre, int hoja) {
        return normalizar("Fravega", nombre, hoja);
    }

    private Product normalizar(String sitio, String nombre, int hoja) {
        Product p = Product.builder().sitio(sitio).nombre(nombre).url("https://x/" + nombre).sitioCategoriaOrigenId(hoja).build();
        return normalizer.normalizar(List.of(p)).get(0);
    }

    @Test
    @DisplayName("an abbreviated name that says no category or sub takes the leaf's category and sub, in electrodomesticos")
    void leafGivesCategoryAndSub() {
        Product p = normalizar("FREI. GAFA FGHF101B 96 L BLANCO", FREEZERS);

        assertThat(p.categoria()).isEqualTo("Heladera");
        assertThat(p.subCategoria()).isEqualTo("Freezer");
        assertThat(p.rubro()).isEqualTo("electrodomesticos");
    }

    @Test
    @DisplayName("the leaf wins over a name that reads as another category")
    void leafOutranksTheName() {
        Product p = normalizar("Procesador de alimentos Atma 400 W", PEQUENOS);

        assertThat(p.categoria()).isEqualTo("Pequeño Electrodoméstico");
    }

    @Test
    @DisplayName("control: a store outside SITIOS_DONDE_LA_HOJA_GANA lets the name override the same leaf, so Fravega's result comes from the set")
    void controlWithoutTheSet() {
        String nombre = "Mouse Logitech M110 Silent";
        assertThat(normalizar("Fravega", nombre, FREEZERS).categoria()).isEqualTo("Heladera");
        assertThat(normalizar("Easy", nombre, FREEZERS).categoria()).isEqualTo("Mouse");
    }
}
