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
@Feature("Rubro electrodomesticos")
@DisplayName("electrodomesticos through the normalizer: home-only stores, Samsung spare parts, Cetrogar and the fields that must not move")
class ElectrodomesticosNormalizacionTest {

    private static final String ELECTRO = "electrodomesticos";
    private static final String TECNOLOGIA = "tecnologia";
    private static final String PEQUENO = "Pequeño Electrodoméstico";
    private static final String OTROS = "Otros";
    private static final int HOJA_HELADERA = 1;
    private static final int HOJA_PARTES = 2;
    private static final int HOJA_TAB = 3;
    private static final int HOJA_SIN_MAPEAR = 4;

    private static SiteRegistry.Sitio sitio(String clave, String forzado, String porDefecto) {
        return new SiteRegistry.Sitio(clave, clave, "vtex", false, forzado, "config", false, porDefecto);
    }

    private final NormalizerService normalizer = normalizer();

    private static NormalizerService normalizer() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "arredo", sitio("arredo", null, "hogar"),
                "somosrex", sitio("somosrex", null, "construccion"),
                "samsung", sitio("samsung", null, TECNOLOGIA),
                "cetrogar", sitio("cetrogar", null, ELECTRO),
                "libre", sitio("libre", null, null),
                "venex", sitio("venex", TECNOLOGIA, null)));
        SitioCategoriaPort arbol = mock(SitioCategoriaPort.class);
        Map<Integer, String> hojas = Map.of(HOJA_HELADERA, "Heladera", HOJA_PARTES, PEQUENO, HOJA_TAB, OTROS);
        for (String nombre : List.of("Arredo", "Somosrex", "Samsung", "Cetrogar")) {
            when(arbol.categoriaPorOrigen(nombre)).thenReturn(hojas);
        }
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(), TestRubros.resolver(registry),
                new GymratTagger(), new SportResolver(), registry, arbol);
    }

    private Product normalizar(String sitio, String nombre, Integer hoja) {
        Product p = Product.builder().sitio(sitio).nombre(nombre).url("https://x/" + sitio + nombre)
                .sitioCategoriaOrigenId(hoja).build();
        return normalizer.normalizar(List.of(p)).get(0);
    }

    @Test
    @DisplayName("a home-only store keeps an appliance named by the name and by the leaf, instead of sending it to Otros")
    void homeOnlyStoreKeepsAppliances() {
        for (String sitio : List.of("Arredo", "Somosrex")) {
            Product porNombre = normalizar(sitio, "Heladera No Frost 400 Litros", HOJA_SIN_MAPEAR);
            assertThat(porNombre.categoria()).as("%s by name", sitio).isEqualTo("Heladera");
            assertThat(porNombre.rubro()).isEqualTo(ELECTRO);

            Product porHoja = normalizar(sitio, "Modelo Compacto Plateado", HOJA_HELADERA);
            assertThat(porHoja.categoria()).as("%s by leaf", sitio).isEqualTo("Heladera");
            assertThat(porHoja.rubro()).isEqualTo(ELECTRO);
        }
    }

    @Test
    @DisplayName("a home-only store still sends what is neither home nor appliance to Otros")
    void homeOnlyStoreStillGuards() {
        assertThat(normalizar("Arredo", "Portaplato Tejido Liso", HOJA_SIN_MAPEAR).categoria()).isEqualTo(OTROS);
    }

    @Test
    @DisplayName("Samsung's appliance spare parts are Pequeño Electrodoméstico, Accesorio y Repuesto, electrodomesticos")
    void samsungSpareParts() {
        for (String nombre : List.of("Anaquel de Puerta de Freezer", "Cajón Inferior de Freezer", "Filtro + Canasta porta Filtro de Aspiradora",
                "Estante de vidrio estándar de Heladeras BESPOKE")) {
            Product p = normalizar("Samsung", nombre, HOJA_PARTES);
            assertThat(p.categoria()).as(nombre).isEqualTo(PEQUENO);
            assertThat(p.subCategoria()).as(nombre).isEqualTo("Accesorio y Repuesto");
            assertThat(p.rubro()).as(nombre).isEqualTo(ELECTRO);
        }
        Product plato = normalizar("Samsung", "Plato para Microondas", HOJA_PARTES);
        assertThat(plato.categoria()).isEqualTo(PEQUENO);
        assertThat(plato.rubro()).isEqualTo(ELECTRO);
    }

    @Test
    @DisplayName("Samsung's Tab covers and TV remotes stay Otros in tecnologia: the site's default is not electrodomesticos")
    void samsungTechnologyStaysTechnology() {
        for (String nombre : List.of("Funda Smart Book Tab S9 Ultra", "Control Remoto para Smart TV", "Funda para The Freestyle")) {
            Product p = normalizar("Samsung", nombre, HOJA_TAB);
            assertThat(p.categoria()).as(nombre).isEqualTo(OTROS);
            assertThat(p.rubro()).as(nombre).isEqualTo(TECNOLOGIA);
        }
    }

    @Test
    @DisplayName("Cetrogar: a row no category decides is an appliance, a Heladera is too, a TV sub moves and a Notebook does not")
    void cetrogar() {
        assertThat(normalizar("Cetrogar", "Casco Moto Integral", HOJA_SIN_MAPEAR).rubro()).isEqualTo(ELECTRO);
        assertThat(normalizar("Cetrogar", "Heladera No Frost 400 Litros", HOJA_HELADERA).rubro()).isEqualTo(ELECTRO);
        Product notebook = normalizar("Cetrogar", "Notebook Lenovo IdeaPad 15 Ryzen 5", HOJA_SIN_MAPEAR);
        assertThat(notebook.categoria()).isEqualTo("Notebook");
        assertThat(notebook.rubro()).isEqualTo(TECNOLOGIA);
    }

    @Test
    @DisplayName("moving rubro does not change gender, sport, gymrat, brand or pack of a TV, a speaker or a fridge: same as on a tecnologia store")
    void otherFieldsDoNotMove() {
        for (String nombre : List.of("Smart TV Samsung 55 Crystal UHD 4K", "Parlante Bluetooth JBL Flip 6 Negro", "Heladera Gafa No Frost 360 Litros")) {
            Product libre = normalizar("libre", nombre, null);
            Product tecnologia = normalizar("venex", nombre, null);

            assertThat(libre.rubro()).as(nombre).isEqualTo(ELECTRO);
            assertThat(tecnologia.rubro()).as(nombre).isEqualTo(TECNOLOGIA);
            assertThat(libre.genero()).as(nombre).isEqualTo(tecnologia.genero());
            assertThat(libre.deporte()).as(nombre).isEqualTo(tecnologia.deporte()).isEmpty();
            assertThat(libre.gymrat()).as(nombre).isEqualTo(tecnologia.gymrat()).isFalse();
            assertThat(libre.marca()).as(nombre).isEqualTo(tecnologia.marca());
            assertThat(libre.cantidadUnidades()).as(nombre).isEqualTo(tecnologia.cantidadUnidades());
            assertThat(libre.categoria()).as(nombre).isEqualTo(tecnologia.categoria());
        }
    }
}
