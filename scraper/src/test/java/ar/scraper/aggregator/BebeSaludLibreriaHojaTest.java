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
@Feature("Category")
@DisplayName("V144: on a store whose curated leaf wins, a leaf left at Otros (or filed under a camera or a monitor) yields to a baby item, a health device or stationery named by the name")
class BebeSaludLibreriaHojaTest {

    private static final int BEBES_Y_NINOS = 1;
    private static final int BALANZAS = 2;
    private static final int CUADERNOS = 3;
    private static final int SEGURIDAD = 4;
    private static final int MONITORES = 5;
    private static final int HELADERAS = 6;

    private final NormalizerService normalizer = normalizer();

    private static NormalizerService normalizer() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "naldo", new SiteRegistry.Sitio("Naldo", "naldo", "vtex", false, null, "db", false, null)));
        SitioCategoriaPort arbol = mock(SitioCategoriaPort.class);
        when(arbol.categoriaPorOrigen("Naldo")).thenReturn(Map.of(BEBES_Y_NINOS, "Otros", BALANZAS, "Otros", CUADERNOS, "Otros",
                SEGURIDAD, "Cámara", MONITORES, "Monitor", HELADERAS, "Heladera"));
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(), TestRubros.resolver(registry),
                new GymratTagger(), new SportResolver(), registry, arbol);
    }

    private Product normalizar(String nombre, int hoja) {
        Product p = Product.builder().sitio("Naldo").nombre(nombre).url("https://x/" + nombre).sitioCategoriaOrigenId(hoja).build();
        return normalizer.normalizar(List.of(p)).get(0);
    }

    @Test
    @DisplayName("a stroller, a scale, a notebook under an 'Otros' leaf are Artículo de Bebé, Salud and Librería, in the rubro of each")
    void otrosLeafYields() {
        Product cochecito = normalizar("Cochecito Bebesit LUGGY Gris", BEBES_Y_NINOS);
        assertThat(cochecito.categoria()).isEqualTo("Artículo de Bebé");
        assertThat(cochecito.rubro()).isEqualTo("juguetes");

        Product balanza = normalizar("Balanza de Baño Aspen Anti Slip B15", BALANZAS);
        assertThat(balanza.categoria()).isEqualTo("Salud");
        assertThat(balanza.rubro()).isEqualTo("belleza");

        Product cuaderno = normalizar("Cuaderno Rivadavia Abc 50 Hojas Araña Verde Cuadriculado", CUADERNOS);
        assertThat(cuaderno.categoria()).isEqualTo("Librería");
        assertThat(cuaderno.rubro()).isEqualTo("oficina");
    }

    @Test
    @DisplayName("a baby monitor under a security-camera or monitor leaf is Artículo de Bebé; an ordinary monitor keeps the leaf")
    void cameraAndMonitorLeavesYieldToABabyMonitor() {
        assertThat(normalizar("Monitor para Bebés See Pro Maxi-Cosi", SEGURIDAD).categoria()).isEqualTo("Artículo de Bebé");
        assertThat(normalizar("Monitor para Bebés See HD", MONITORES).categoria()).isEqualTo("Artículo de Bebé");
        assertThat(normalizar("Monitor Gamer Samsung Odyssey G5 27 144hz", MONITORES).categoria()).isEqualTo("Monitor");
    }

    @Test
    @DisplayName("any other leaf keeps winning: a fridge leaf is not a baby item because the name says a bottle")
    void otherLeavesStillWin() {
        assertThat(normalizar("Heladera Gafa con freezer Cíclica 252 L", HELADERAS).categoria()).isEqualTo("Heladera");
    }
}
