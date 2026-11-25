package ar.scraper.aggregator.normalize;

import ar.scraper.testsupport.TestRubros;

import ar.scraper.classification.CategoryGroups;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.classification.SiteRegistry.Sitio;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Normalization")
@Feature("Sub-category")
@DisplayName("Silla: sub-category by name and the rubro that sub decides")
class SillaSubcategoriaTest {

    private final SubcategoryResolver subs = new SubcategoryResolver();

    private final RubroResolver rubros = TestRubros.resolver(SiteRegistry.forTesting(Map.of(
            "easy", new Sitio("Easy", "easy", "vtex", false, null, "config"),
            "venex", new Sitio("Venex", "venex", "osc", false, "tecnologia", "config"),
            "zentra", new Sitio("Zentra", "zentra", "tiendanube", false, "oficina", "config"),
            "ambiancedeco", new Sitio("Ambiancedeco", "ambiancedeco", "ambiancedeco", false, "hogar", "config"),
            "mrplacadeco", new Sitio("Mrplacadeco", "mrplacadeco", "tiendanube", false, "construccion", "config"))));

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("each sub, from names of the real catalogue")
    @CsvSource(delimiter = '|', value = {
            "Silla Gamer Evolabs Graphen One|gamer",
            "SILLA GAMER RAPTOR THRONE R20 - TELA NEGRA|gamer",
            "Silla Gaming Alleyg Bazil Ag85br|gamer",
            "Silla de escritorio Mint neumática giratoria tela Negra|oficina",
            "Sillon Ergonomico Vice White|oficina",
            "silla pc hope c/bzo gris arg|oficina",
            "Silla de Oficina Reclinable Gerencial con Apoyapies|oficina",
            "Silla de Comer Infanti Appetito Estrellas|infantil",
            "Silla Eames clásica para ninos color rojo|infantil",
            "Sillas Montessori Infantil Madera Melamina|infantil",
            "Silla Mecedora Kori 2 en 1 Eco Maxi Cosi|infantil",
            "Silla Plegable Tipo Booster Con Asiento De Plastico|infantil",
            "Silla de comedor Smile con brazos color blanco pack x 2 U|comedor",
            "Set x 4 Sillas Blancas Eams|comedor",
            "Set x2 Silla Eames New Tower Tulip Negra|comedor",
            "Silla para Bar 43x43x95 Cm Hierro Negro Spot Cooper M+Design|comedor",
            "Taburete Banqueta Ombu pvc negro|comedor",
            "Silla Mecedora 70x45x50 Cm Polipropileno Blanco Eames Decototale|comedor",
            "Sillas de comedor/jardin Allegra gris|comedor",
            "Silla de Jardin Plegable De Calanda Midtown|exterior",
            "Silla Playa Acero y Poliéster 1 Plaza Multicolor Outzen|exterior",
            "Silla Plegable Director Reforzada 01759|exterior",
            "Silla Plegable Gadnic Reforzada Ideal Camping Pesca Con Portavaso|exterior",
            "Silla Plástico Rattan 1 Plaza Plásticos Poo|exterior",
            "Silla Plegable Hierro y Madera 1 Plaza Maderber|exterior",
    })
    void sub(String nombre, String esperada) {
        assertThat(subs.resolver(nombre, "Silla")).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("precedence: gamer, oficina, infantil, strong outdoor word, comedor, plain plegable")
    @CsvSource(delimiter = '|', value = {
            "Silla gamer de escritorio giratoria|gamer",
            "Sillon Silla Escritorio Gamer Playstation Giratoria Oficina|gamer",
            "Sillon Director Broksol Ejecutivo Premium Plegable|oficina",
            "Silla eames clásica para ninos|infantil",
            "Set de 4 Banquetas Plegables Portátiles Reforzadas para Camping Rojas|exterior",
            "Silla de comedor plegable|comedor",
            "Set Camping de Mesa Plegable + 2 Sillones Director Portátil|exterior",
    })
    void precedence(String nombre, String esperada) {
        assertThat(subs.resolver(nombre, "Silla")).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "{0}")
    @DisplayName("a name with no signal abstains")
    @CsvSource(delimiter = '|', value = {
            "Silla Aimaretti Dakar",
            "Silla 76x55x45 Cm Polipropileno Blanca Moon Decototale",
            "Silla Shaf Fiji 1252/4",
    })
    void abstains(String nombre) {
        assertThat(subs.resolver(nombre, "Silla")).isEmpty();
    }

    @Test
    @DisplayName("the signals only read chairs: the same words on another category give no chair sub")
    void onlyChairs() {
        assertThat(subs.resolver("Mesa Comedor Plegable", "Mesa")).isNotIn("gamer", "oficina", "comedor", "exterior", "infantil");
    }

    @Test
    @DisplayName("home subs make a chair hogar, gamer and oficina keep it oficina, no sub keeps today's rubro")
    void rubroBySub() {
        for (String sub : new String[]{"comedor", "exterior", "infantil"}) {
            assertThat(rubros.resolver("easy", "Silla", "", sub)).as(sub).isEqualTo("hogar");
        }
        for (String sub : new String[]{"gamer", "oficina", ""}) {
            assertThat(rubros.resolver("easy", "Silla", "", sub)).as("sub '%s'", sub).isEqualTo("oficina");
        }
        assertThat(rubros.resolver("easy", "Silla", "", null)).isEqualTo("oficina");
        assertThat(rubros.resolver("easy", "Silla", "")).isEqualTo("oficina");
    }

    @Test
    @DisplayName("a site with a forced rubro keeps it whatever the sub says")
    void forcedRubroStillWins() {
        for (String sitio : new String[]{"venex", "zentra", "ambiancedeco", "mrplacadeco"}) {
            String esperado = switch (sitio) {
                case "venex" -> "tecnologia";
                case "zentra" -> "oficina";
                case "ambiancedeco" -> "hogar";
                default -> "construccion";
            };
            for (String sub : new String[]{"comedor", "exterior", "infantil", "gamer", "oficina", ""}) {
                assertThat(rubros.resolver(sitio, "Silla", "", sub)).as("%s / %s", sitio, sub).isEqualTo(esperado);
            }
        }
    }

    @Test
    @DisplayName("the table has no entry for another category or an unknown sub")
    void tableIsClosed() {
        assertThat(TestRubros.catalogo().fila("Mesa", "comedor")).isEmpty();
        assertThat(TestRubros.catalogo().fila("Silla", "gamer")).isEmpty();
        assertThat(TestRubros.catalogo().fila(null, "comedor")).isEmpty();
        assertThat(TestRubros.catalogo().fila("Silla", null).orElseThrow().rubro()).as("a null sub is the category itself").isEqualTo("oficina");
    }
}
