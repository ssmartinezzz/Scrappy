package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** V144: the sub of a washing machine, cooker, fridge, heater and water heater over real catalogue names. */
@Epic("Normalization")
@Feature("Sub-category")
@DisplayName("Grandes electrodomésticos — sub-category by noun, in the precedence the vocabulary lists")
class GrandesElectroSubcategoryResolverTest {

    private final SubcategoryResolver resolver = new SubcategoryResolver();

    @ParameterizedTest(name = "[{index}] {0}: \"{1}\" -> \"{2}\"")
    @CsvSource(delimiter = '|', value = {
        "Lavarropas | Lavavajillas Whirlpool Inverter 14 Cubiertos Inox                   | Lavavajillas",
        "Lavarropas | Secarropas Centrífugo Kohinoor 6.5Kg Acero Inoxidable A-665/2        | Secarropas",
        "Lavarropas | Combo Codini Silent 4051 10 kg + Secarropas IN65 6.5 kg Negro        | Secarropas",
        "Lavarropas | Lavasecarropas Codini BIAFB10014B 10/7 kg 1400 RPM Blanco            | Lavarropas",
        "Lavarropas | Carga Superior Lavarropas Digital Inverter                           | Lavarropas",
        "Lavarropas | Lavarropas Semiautomático Codini QBICO 10 kg 6 Funciones 8053        | Lavarropas",
        "Cocina y Horno | Microondas Midea Mw-Dn120gar1                                    | Microondas",
        "Cocina y Horno | Anafe Eléctrico 4 Hornallas T-04 53898 60 cm Negro               | Anafe",
        "Cocina y Horno | Cocina Escorial Candor S2 Gas Envasado Puerta C/ Visor 51cm Negro | Cocina",
        "Cocina y Horno | Horno Electrico Yelmo Yl-45N                                      | Horno",
        "Cocina y Horno | Combo68 Longvie Horno Eléctrico He6900g + Anafe A6600gf           | Horno",
        "Cocina y Horno | Hornito Eléctrico Ultracomb UC50C 50lt 1600W convección          | Horno",
        "Heladera | Heladera con freezer Cíclica 364 L Blanca Drean HDR370F50B              | Heladera",
        "Heladera | Bespoke AI Freezer Inferior Heladera AI Energy Mode SpaceMax 409L       | Heladera",
        "Heladera | Freezer Horizontal Gafa Chest Freezer 496 lt                            | Freezer",
        "Heladera | Cava De Vino Coolfroz Gadnic WCTE0008 8 Botellas Termoelectrica         | Cava",
        "Heladera | Exhibidora Vertical Briket Master 4300 417 L blanca                     | Exhibidora",
        "Heladera | Frigobar Gafa 90L Efficient Plata HGFM90P                               | Heladera",
        "Calefactor | Calefactor Tiro Balanceado en U Calden Tbu 5.0 Gn Blanco Smart 5000 Kcal/H | Tiro Balanceado",
        "Calefactor | Estufa Tiraje Balanceado 3800 Kcal/H Gas Natural Gris 43712V Volcan   | Tiro Balanceado",
        "Calefactor | Calefactor Sin Salida Calden 5000 Kcal Ge Negro 5000 Kcal/H           | Sin Salida",
        "Calefactor | Calefactor Eskabe 3000 Miniconvex Siglo21 S/Salida S21Mx3P            | Sin Salida",
        "Calefactor | Caloventor de Pared Longvie Ee2k                                      | Caloventor",
        "Calefactor | Panel Convector Eléctrico 2000 W 3 Niveles Negro NEX                   | Convector",
        "Calefactor | Panel Calefactor Ecosol 500w Quadrans De Pie                           | Panel",
        "Calefactor | Calefactor Estufa Exterior Patio A Gas Tipo Hongo Cilindrico           | Estufa",
        "Termotanque | Termotanque Electrico Longvie Te80d                                   | Eléctrico",
        "Termotanque | Termotanque multigas Eskabe H700SV 40 lt encendido eléctrico          | A Gas",
        "Termotanque | Termotanque Gas Natural 160 Lts 13000 Kcal/h Gris AP160 Rheem          | A Gas",
        "Termotanque | Termotanque Rheem Electrico De Pie 155 L - Tep155rh                    | Eléctrico",
    })
    void subByNoun(String categoria, String nombre, String sub) {
        assertThat(resolver.resolver(nombre, categoria)).isEqualTo(sub);
    }

    @ParameterizedTest(name = "[{index}] {0}: \"{1}\" keeps no sub")
    @CsvSource(delimiter = '|', value = {
        "Calefactor | Chimenea Hogar A Gas Envasado Calden Paris Colgar 90 Cm Ge Negro",
        "Calefactor | Salamandra Tromen Austral 7000 Calefactor A Leña + Kit De Instalación Pared 4\"",
        "Calefactor | Calefactor Tiro Natural Calden Tn 6.0 Ge Negro Smart 6000 Kcal/H",
        "Cocina y Horno | Combo108 Eléctrico HEQB60X - AVST430K",
        "Termotanque | Termotanque Marshall Tsz-30",
    })
    void unclaimedNamesStayBlank(String categoria, String nombre) {
        assertThat(resolver.resolver(nombre, categoria)).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] other category: \"{0}\" gets no appliance sub")
    @CsvSource(delimiter = '|', value = {
        "Heladera Gafa con freezer, in another category | Mueble",
        "Lavarropas Drean 5 kg | Zapatilla",
    })
    void onlyTheFiveCategories(String nombre, String categoria) {
        assertThat(resolver.resolver(nombre, categoria)).isNotIn("Heladera", "Lavarropas", "Freezer");
    }
}
