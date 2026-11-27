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
@Feature("Sub-category")
@DisplayName("V144: a curated leaf's sub fills a blank sub only when the row ends in the leaf's own category; a name sub always wins")
class SubDeLaHojaTest {

    private static final int FREEZERS = 1;
    private static final int SIN_SUB = 2;

    private final NormalizerService normalizer = normalizer();

    private static NormalizerService normalizer() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "oncity", new SiteRegistry.Sitio("Oncity", "oncity", "vtex", false, null, "db", false, "electrodomesticos"),
                "easy", new SiteRegistry.Sitio("Easy", "easy", "vtex", false, null, "db", false, null)));
        SitioCategoriaPort arbol = mock(SitioCategoriaPort.class);
        for (String sitio : List.of("Oncity", "Easy")) {
            when(arbol.categoriaPorOrigen(sitio)).thenReturn(Map.of(FREEZERS, "Heladera", SIN_SUB, "Heladera"));
            when(arbol.subCategoriaPorOrigen(sitio)).thenReturn(Map.of(FREEZERS, "Freezer"));
        }
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(), TestRubros.resolver(registry),
                new GymratTagger(), new SportResolver(), registry, arbol);
    }

    private Product normalizar(String sitio, String nombre, Integer hoja) {
        Product.ProductBuilder p = Product.builder().sitio(sitio).nombre(nombre).url("https://x/" + sitio + nombre);
        if (hoja != null) p.sitioCategoriaOrigenId(hoja);
        return normalizer.normalizar(List.of(p.build())).get(0);
    }

    @Test
    @DisplayName("a name that says no sub takes the leaf's, on a store whose leaf wins and on one whose name wins")
    void leafFillsABlankSub() {
        Product oncity = normalizar("Oncity", "Gafa FGHF101B 96 L Blanco", FREEZERS);
        assertThat(oncity.categoria()).isEqualTo("Heladera");
        assertThat(oncity.subCategoria()).isEqualTo("Freezer");

        Product easy = normalizar("Easy", "Gafa FGHF101B 96 L Blanco", FREEZERS);
        assertThat(easy.categoria()).isEqualTo("Heladera");
        assertThat(easy.subCategoria()).isEqualTo("Freezer");
    }

    @Test
    @DisplayName("a sub the name gives always wins over the leaf's")
    void nameSubWins() {
        Product p = normalizar("Oncity", "Heladera Samsung Bespoke 384 L", FREEZERS);

        assertThat(p.categoria()).isEqualTo("Heladera");
        assertThat(p.subCategoria()).isEqualTo("Heladera");
    }

    @Test
    @DisplayName("a leaf without a sub, and a row without a leaf, leave the sub blank")
    void nothingToFill() {
        assertThat(normalizar("Oncity", "Gafa FGHF101B 96 L Blanco", SIN_SUB).subCategoria()).isEmpty();
        assertThat(normalizar("Oncity", "Gafa FGHF101B 96 L Blanco", null).subCategoria()).isEmpty();
    }

    @Test
    @DisplayName("when the name sends the row to another category the leaf's sub is not applied to it")
    void otherCategoryIgnoresTheLeafSub() {
        Product p = normalizar("Easy", "Mouse Logitech M110 Silent", FREEZERS);

        assertThat(p.categoria()).isEqualTo("Mouse");
        assertThat(p.subCategoria()).isEmpty();
    }

    @Test
    @DisplayName("the same name normalized twice gives the same sub: renormalizar reads the leaf again and changes nothing")
    void renormalizarIsStable() {
        Product primera = normalizar("Oncity", "Gafa FGHF101B 96 L Blanco", FREEZERS);
        Product segunda = normalizer.normalizar(List.of(primera.toBuilder().sitioCategoriaOrigenId(FREEZERS).build())).get(0);

        assertThat(segunda.subCategoria()).isEqualTo(primera.subCategoria());
        assertThat(segunda.categoria()).isEqualTo(primera.categoria());
    }
}
