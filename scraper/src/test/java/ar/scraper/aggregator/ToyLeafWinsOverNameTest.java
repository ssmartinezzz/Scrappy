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
@DisplayName("El Mundo del Juguete: a toy leaf beats the name, nothing else does")
class ToyLeafWinsOverNameTest {

    private static final int HOJA_JUGUETE = 1;
    private static final int HOJA_NO_JUGUETE = 2;
    private static final int HOJA_SIN_MAPEAR = 3;

    private final NormalizerService normalizer = normalizer();

    private static NormalizerService normalizer() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "elmundodeljuguete", new SiteRegistry.Sitio("Elmundodeljuguete", "elmundodeljuguete",
                        "vtex", false, null, "db", false, "juguetes"),
                "easy", new SiteRegistry.Sitio("Easy", "easy", "vtex", false, null, "db", false, "construccion")));
        SitioCategoriaPort arbol = mock(SitioCategoriaPort.class);
        // HOJA_SIN_MAPEAR is absent on purpose: an unmapped leaf has no curated category.
        Map<Integer, String> hojas = Map.of(HOJA_JUGUETE, "Juguete", HOJA_NO_JUGUETE, "Mochila");
        when(arbol.categoriaPorOrigen("Elmundodeljuguete")).thenReturn(hojas);
        when(arbol.categoriaPorOrigen("Easy")).thenReturn(hojas);
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(), new RubroResolver(registry),
                new GymratTagger(), new SportResolver(), registry, arbol);
    }

    private Product normalizar(String sitio, String nombre, int hoja) {
        Product p = Product.builder().sitio(sitio).nombre(nombre).url("https://x/" + nombre)
                .sitioCategoriaOrigenId(hoja).build();
        return normalizer.normalizar(List.of(p)).get(0);
    }

    private String nombreAmbiguo(String nombre) {
        // Guard: the name alone must classify as something other than a toy, or the test proves nothing.
        return new CategoryClassifier().normalizarCategoria(null, nombre);
    }

    @Test
    @DisplayName("names that read as clothing, garden, perfume or food lose to a toy leaf")
    void toyLeafBeatsMisleadingNames() {
        for (String nombre : List.of("Set De Bloques 120 Piezas", "Pack 2 Autos Metal",
                "Figura Marvel Shuri 13 cm con luz", "Bebote Realista con Vestido",
                "Super Lanzador de Agua Base-X Splash")) {
            assertThat(nombreAmbiguo(nombre)).as("name alone: " + nombre).isNotEqualTo("Juguete");
            Product p = normalizar("Elmundodeljuguete", nombre, HOJA_JUGUETE);
            assertThat(p.categoria()).as(nombre).isEqualTo("Juguete");
            assertThat(p.rubro()).as(nombre).isEqualTo("juguetes");
        }
    }

    @Test
    @DisplayName("a leaf mapped to a non-toy category does not beat the name")
    void nonToyLeafKeepsNameFirst() {
        assertThat(normalizar("Elmundodeljuguete", "Set De Bloques 120 Piezas", HOJA_NO_JUGUETE).categoria())
                .isEqualTo(nombreAmbiguo("Set De Bloques 120 Piezas"));
    }

    @Test
    @DisplayName("an unmapped leaf keeps the name classification")
    void unmappedLeafKeepsNameFirst() {
        assertThat(normalizar("Elmundodeljuguete", "Set De Bloques 120 Piezas", HOJA_SIN_MAPEAR).categoria())
                .isEqualTo(nombreAmbiguo("Set De Bloques 120 Piezas"));
    }

    @Test
    @DisplayName("the same product on another VTEX site keeps the name classification")
    void otherSitesKeepNameFirst() {
        assertThat(normalizar("Easy", "Set De Bloques 120 Piezas", HOJA_JUGUETE).categoria())
                .isEqualTo(nombreAmbiguo("Set De Bloques 120 Piezas"));
    }
}
