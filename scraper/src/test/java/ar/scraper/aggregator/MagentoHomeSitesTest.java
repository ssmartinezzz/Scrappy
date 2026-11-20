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
import ar.scraper.classification.SiteRegistry.Sitio;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Epic("Normalization")
@Feature("Per-site rules")
@DisplayName("Somos Rex: the Magento page tags every card 'mujer', this store has no gender")
class MagentoHomeSitesTest {

    private final NormalizerService normalizer = normalizer();

    private static NormalizerService normalizer() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "somosrex", new Sitio("Somosrex", "somosrex", "magento", false, null, "config", false, "construccion")));
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(), new RubroResolver(registry),
                new GymratTagger(), new SportResolver(), registry, mock(SitioCategoriaPort.class));
    }

    private Product normalizar(String sitio, String nombre) {
        Product p = Product.builder().sitio(sitio).nombre(nombre).precio(1000.0).url("https://x.test/" + nombre.hashCode())
                .categoria("").genero("mujer").talles(List.of()).build();
        return normalizer.normalizar(List.of(p)).get(0);
    }

    @Test
    @DisplayName("the scraped 'mujer' does not reach a drill")
    void noGender() {
        assertThat(normalizar("Somosrex", "Taladro Percutor Black Decker 10mm").genero()).isEmpty();
    }

    @Test
    @DisplayName("Somos Rex: a paint sold by weight is a paint, not food, and a wool roller is not a sweater")
    void somosrexPaintIsNotFoodOrClothing() {
        for (String nombre : List.of("Latex Exterior Duralba Super Acrilico Blanco 20 Lts Alba",
                "Cetol Classic Balance Ultraprotector Brillante 4 L Roble", "Esmalte Sintetico Vitrolux Brillante Negro 4 Lts Colorin")) {
            Product p = normalizar("Somosrex", nombre);
            assertThat(p.categoria()).as(nombre).isEqualTo("Pintura");
            assertThat(p.rubro()).as(nombre).isEqualTo("construccion");
        }
        assertThat(normalizar("Somosrex", "Membrana Liquida Para Techos Fibrado Verde Plavicon 25 Kg").categoria()).isEqualTo("Impermeabilizante");

        Product sinNombreDeCategoria = normalizar("Somosrex", "Poximix 15 Minutos Uso Exterior Repara Grietas 5 Kg");
        assertThat(sinNombreDeCategoria.categoria()).isEqualTo("Otros");
        assertThat(sinNombreDeCategoria.rubro()).isEqualTo("construccion");

        Product rodillo = normalizar("Somosrex", "Repuesto Rodillo Lana 22 Cm Wolly Oro");
        assertThat(rodillo.categoria()).isNotEqualTo("Sweater");
        assertThat(rodillo.rubro()).isEqualTo("construccion");
    }

    @Test
    @DisplayName("Somos Rex keeps its sports and home categories")
    void somosrexKeepsHomeAndSports() {
        assertThat(normalizar("Somosrex", "Paleta de Pádel Bullpadel Vertex 04").rubro()).isEqualTo("deporte");
        assertThat(normalizar("Somosrex", "Termotanque Eléctrico Sherman 55 Lts De Pie TEPC055").rubro()).isEqualTo("hogar");
    }
}
