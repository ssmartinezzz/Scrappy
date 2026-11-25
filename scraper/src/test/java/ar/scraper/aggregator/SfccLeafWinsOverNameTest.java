package ar.scraper.aggregator;

import ar.scraper.testsupport.TestRubros;

import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.GymratTagger;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.aggregator.normalize.SportResolver;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.catalog.SitioCategoriaPort;
import ar.scraper.classification.BrandExtractor;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.classification.SiteRegistry.Sitio;
import ar.scraper.model.Product;
import ar.scraper.pages.SfccPage;
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
@Feature("Per-site rules")
@DisplayName("NormalizerService: on Dexter and Moov a curated accessory leaf outranks the name; an unmapped leaf leaves the name in charge")
class SfccLeafWinsOverNameTest {

    private static final int PACK_DE_MEDIAS = SfccPage.idDeHoja("accesorios-packdemedias");
    private static final int ZAPATILLAS = SfccPage.idDeHoja("calzado-zapatillas");

    private static NormalizerService normalizer(String sitioKey, String nombre) {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(sitioKey,
                new Sitio(nombre, sitioKey, "sfcc", false, null, "config", false)));
        SitioCategoriaPort arbol = mock(SitioCategoriaPort.class);
        when(arbol.categoriaPorOrigen(nombre)).thenReturn(Map.of(PACK_DE_MEDIAS, "Medias"));
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(),
                TestRubros.resolver(registry), new GymratTagger(), new SportResolver(), registry, arbol);
    }

    private static Product producto(String sitio, String nombre, Integer origen) {
        return Product.builder().sitio(sitio).nombre(nombre).precio(1000.0).url("http://" + sitio + ".test/" + nombre.hashCode())
                .categoria("").genero("").talles(List.of()).sitioCategoriaOrigenId(origen).build();
    }

    @Test
    @DisplayName("a pack of socks the name reads as Conjunto is Medias on the curated leaf, on both sites")
    void packOfSocksIsMedias() {
        String nombre = "Pack de Medias adidas Clasicos X3";

        assertThat(normalizer("dexter", "Dexter").normalizar(List.of(producto("Dexter", nombre, null))).get(0).categoria())
                .as("without the leaf the name is read as a set").isEqualTo("Conjunto");
        assertThat(normalizer("dexter", "Dexter").normalizar(List.of(producto("Dexter", nombre, PACK_DE_MEDIAS))).get(0).categoria())
                .isEqualTo("Medias");
        assertThat(normalizer("moov", "Moov").normalizar(List.of(producto("Moov", nombre, PACK_DE_MEDIAS))).get(0).categoria())
                .isEqualTo("Medias");
    }

    @Test
    @DisplayName("negative: a leaf with no curated category (footwear) leaves the name in charge")
    void unmappedLeafLeavesTheName() {
        Product p = normalizer("dexter", "Dexter").normalizar(
                List.of(producto("Dexter", "Zapatillas Running Nike Air Zoom Pegasus 40 Mujer", ZAPATILLAS))).get(0);

        assertThat(p.categoria()).isEqualTo("Zapatilla Running");
    }
}
