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
@DisplayName("Arredo: in a home-textile store only a home category from the name counts, otherwise the leaf decides")
class ArredoSoloHogarTest {

    private static final int HOJA_INDIVIDUALES = 20;
    private static final int HOJA_FUNDAS = 58;
    private static final int HOJA_SIN_MAPEAR = 15;

    private final NormalizerService normalizer = normalizer();

    private static NormalizerService normalizer() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "arredo", new SiteRegistry.Sitio("Arredo", "arredo", "vtex", false, null, "db", false, "hogar")));
        SitioCategoriaPort arbol = mock(SitioCategoriaPort.class);
        when(arbol.categoriaPorOrigen("Arredo")).thenReturn(Map.of(HOJA_INDIVIDUALES, "Bazar", HOJA_FUNDAS, "Funda"));
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(), new RubroResolver(registry),
                new GymratTagger(), new SportResolver(), registry, arbol);
    }

    private Product normalizar(String nombre, int hoja) {
        Product p = Product.builder().sitio("Arredo").nombre(nombre).url("https://x/" + nombre)
                .sitioCategoriaOrigenId(hoja).build();
        return normalizer.normalizar(List.of(p)).get(0);
    }

    @Test
    @DisplayName("a kit or a knit word does not turn a home product into a garment")
    void garmentAndKitNamesYieldToTheLeaf() {
        for (String nombre : List.of("Set x4 Individuales Fibra Natural Lisos", "Portaplato Tejido Liso")) {
            Product p = normalizar(nombre, HOJA_INDIVIDUALES);
            assertThat(p.categoria()).as(nombre).isEqualTo("Bazar");
            assertThat(p.rubro()).as(nombre).isEqualTo("hogar");
        }
        assertThat(normalizar("Funda de Acolchado Algodón Panamá Lisa", HOJA_FUNDAS).categoria()).isEqualTo("Funda");
    }

    @Test
    @DisplayName("a home category from the name still wins over the leaf")
    void homeNameWins() {
        assertThat(normalizar("Toalla con Rombos", HOJA_INDIVIDUALES).categoria()).isEqualTo("Toalla");
    }

    @Test
    @DisplayName("without a mapped leaf the non-home name falls to Otros, still in the hogar rubro")
    void unmappedLeafFallsToOtros() {
        Product p = normalizar("Camino de Mesa Tejido Calado", HOJA_SIN_MAPEAR);
        assertThat(p.categoria()).isEqualTo("Otros");
        assertThat(p.rubro()).isEqualTo("hogar");
    }

    @Test
    @DisplayName("other stores keep the name classifier untouched")
    void otherSitesAreUntouched() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "otro", new SiteRegistry.Sitio("Otro", "otro", "vtex", false, null, "db", false, null)));
        NormalizerService otro = new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(),
                new BrandExtractor(), new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(),
                new RubroResolver(registry), new GymratTagger(), new SportResolver(), registry,
                mock(SitioCategoriaPort.class));
        Product p = Product.builder().sitio("Otro").nombre("Portaplato Tejido Liso").url("https://x/o").build();
        assertThat(otro.normalizar(List.of(p)).get(0).categoria()).isEqualTo("Sweater");
    }
}
