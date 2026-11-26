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
@DisplayName("TR3: a curated leaf that also holds what only shares its noun yields to the name; the rest of the leaf still wins. Names are real Oncity rows (2026-11-26)")
class ResiduosDeTecnologiaHojaTest {

    private static final int FUENTES = 1;
    private static final int PLACAS_DE_VIDEO = 2;
    private static final int SMARTWATCHES = 3;
    private static final int CABLES = 4;
    private static final int TABLETS = 5;

    private final NormalizerService normalizer = normalizer();

    private static NormalizerService normalizer() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "oncity", new SiteRegistry.Sitio("Oncity", "oncity", "vtex", false, null, "db", false, "tecnologia")));
        SitioCategoriaPort arbol = mock(SitioCategoriaPort.class);
        when(arbol.categoriaPorOrigen("Oncity")).thenReturn(Map.of(FUENTES, "Fuente", PLACAS_DE_VIDEO, "GPU",
                SMARTWATCHES, "Reloj", CABLES, "Cable", TABLETS, "Tablet"));
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(), TestRubros.resolver(registry),
                new GymratTagger(), new SportResolver(), registry, arbol);
    }

    private Product normalizar(String nombre, int hoja) {
        Product p = Product.builder().sitio("Oncity").nombre(nombre).url("https://x/" + nombre)
                .sitioCategoriaOrigenId(hoja).build();
        return normalizer.normalizar(List.of(p)).get(0);
    }

    @Test
    @DisplayName("bakeware under 'Fuentes y recipientes' is Bazar, a PC power supply and a USB charger stay Fuente")
    void bakewareLeavesTheFuenteLeaf() {
        assertThat(normalizar("Asadera Panelux 1190023", FUENTES).categoria()).isEqualTo("Bazar");
        assertThat(normalizar("Fuente Pyr-O-Rey 1001403", FUENTES).categoria()).isEqualTo("Bazar");
        assertThat(normalizar("Bandeja Nouvelle Cuisine 1140810", FUENTES).categoria()).isEqualTo("Bazar");
        assertThat(normalizar("Fuente XPG KYBER 750W 80 Plus Gold ATX 3.1 PCIe 5.0 12VHPWR", FUENTES).categoria()).isEqualTo("Fuente");
        assertThat(normalizar("Fuente de alimentación de 1 puerto USB C PD port 20W NISUTA - NSFU20U", FUENTES).categoria()).isEqualTo("Fuente");
    }

    @Test
    @DisplayName("a motherboard and a CPU under 'Placas de video' are what they are; a graphics card still is a GPU")
    void boardsLeaveTheGpuLeaf() {
        assertThat(normalizar("Placa Madre MSI A520M-A PRO AM4 DDR4 Micro ATX", PLACAS_DE_VIDEO).categoria()).isEqualTo("Motherboard");
        assertThat(normalizar("MICRO PROCESADOR AMD RYZEN 7 5700G 4.6GHZ RADEON GRAPHICS 3.8GHZ 16MB AM4", PLACAS_DE_VIDEO).categoria()).isEqualTo("CPU");
        assertThat(normalizar("Placa de Video Gigabyte GeForce RTX 5060 Windforce OC 8G", PLACAS_DE_VIDEO).categoria()).isEqualTo("GPU");
    }

    @Test
    @DisplayName("a watch charger and a watch cover under 'Smartwatches' are Celular; a strap and a watch stay Reloj, the strap with its sub")
    void watchAccessoriesLeaveTheWatchLeaf() {
        assertThat(normalizar("Cargador Para Reloj Mi Band 5 6 7 Xiaomi", SMARTWATCHES).categoria()).isEqualTo("Celular");
        assertThat(normalizar("Funda Para Reloj Xiaomi Redmi Watch 5 Lite Negro", SMARTWATCHES).categoria()).isEqualTo("Celular");
        Product malla = normalizar("Malla Para Reloj Xiaomi Mi Band 5 6 7 Azul Oscuro", SMARTWATCHES);
        assertThat(malla.categoria()).isEqualTo("Reloj");
        assertThat(malla.subCategoria()).isEqualTo("malla");
        assertThat(malla.rubro()).as("a strap is a technology accessory").isEqualTo("tecnologia");
        // "Redmi 5" reads as a phone model to CelularClassifier: only a charger or cover leaves the leaf, never a strap or a watch.
        assertThat(normalizar("Malla Para Reloj Amazfit Gtr 2 3 4 Redmi 5 Lite 5 Active Sport Iman Rojo 908", SMARTWATCHES).categoria()).isEqualTo("Reloj");
        assertThat(normalizar("Reloj Smart Redmi 5 Lite", SMARTWATCHES).categoria()).isEqualTo("Reloj");
        assertThat(normalizar("Smartwatch Samsung Galaxy Watch 6 Classic 43mm BT", SMARTWATCHES).subCategoria()).isEqualTo("smartwatch");
        Product casio = normalizar("Reloj Casio AE-1500WH-5AV Digital Illuminator Beige", SMARTWATCHES);
        assertThat(casio.categoria()).isEqualTo("Reloj");
        assertThat(casio.subCategoria()).isEqualTo("analógico");
        assertThat(casio.rubro()).as("(Reloj, analógico) is indumentaria on a store with no forced rubro").isEqualTo("indumentaria");
    }

    @Test
    @DisplayName("a power strip under 'Cables' is Ficha y Prolongador and a cable stays a Cable; a children's board under 'Tablets' is a toy and a tablet stays one")
    void otherLeavesYield() {
        assertThat(normalizar("Zapatilla Eléctrica Alargue Prolongad Usb Universal Térmica", CABLES).categoria()).isEqualTo("Ficha y Prolongador");
        assertThat(normalizar("Cable alargue USB 2.0 amplificado 20m NISUTA - NSCAEXUS20", CABLES).categoria()).isEqualTo("Cable");
        assertThat(normalizar("Pizarra Mágica Tablet Lcd 9 Pulgadas Infantil", TABLETS).categoria()).isEqualTo("Juguete");
        assertThat(normalizar("Tablet Samsung Galaxy Tab A9 64GB 4GB Ram 8.7 Pulgadas", TABLETS).categoria()).isEqualTo("Tablet");
    }
}
