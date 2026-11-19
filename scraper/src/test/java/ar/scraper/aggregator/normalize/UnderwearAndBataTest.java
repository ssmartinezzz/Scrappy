package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Names are real rows: Sportline (ROPA INTERIOR, Medias) and Arredo (Batas), 2026-11-19; laundry items from the dev catalog. */
@Epic("Normalization")
@Feature("Category classification")
@DisplayName("Underwear by gender, sock cuts and bath robes as a sub of Toalla")
class UnderwearAndBataTest {

    private final CategoryClassifier classifier = new CategoryClassifier();
    private final SubcategoryResolver subs = new SubcategoryResolver();

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
        "Ropa Interior Under Armour Ua Tech  Hombre|Calzoncillos",
        "Ropa Interior Under Armour Ua Cotton  Hombre|Calzoncillos",
        "Ropa Interior Puma Sport Active Hombre|Calzoncillos",
        "Ropa Interior Puma Formstrip  Hombre|Calzoncillos",
        "Ropa Interior Puma Sport Active Mujer|Bombacha",
    })
    @DisplayName("a leading 'Ropa Interior' plus the gender is the garment")
    void leadingUnderwearNoun(String nombre, String esperada) {
        assertThat(classifier.normalizarCategoria("", nombre)).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
        "Bolsa Lavado Ropa Interior 15x16 Cm Poliéster Blanco Cotidiana",
        "Tendedero Ropa Interior 24 Clips Gris Cotidiana",
        "Ropa Interior Under Armour Ua Cotton Unisex",
    })
    @DisplayName("a laundry bag, a clothes line or a genderless row is not underwear")
    void notUnderwear(String nombre) {
        assertThat(classifier.normalizarCategoria("", nombre)).isNotIn("Calzoncillos", "Bombacha");
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Medias Topper Pack X 3 Invisibles Mujer|invisibles",
        "Medias Topper Pack X 3 Soquetes Wmns Mujer|soquete",
        "Medias Topper Pack X 3 Caña Alta Mns Hombre|caña alta",
        "Medias Topper Pack X 3 Caña Baja  Hombre|caña baja",
    })
    @DisplayName("socks carry their cut as sub-category")
    void sockCuts(String nombre, String sub) {
        assertThat(subs.resolver(nombre, "Medias")).isEqualTo(sub);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
        "Bata de Adulto Baby Skin Liso",
        "Bata de Baño Infantil con Capucha Toalla Mickey Bulgarito",
        "Bata Toalla de Bebé Con Capucha Algodón Cisne",
    })
    @DisplayName("a bath robe is a Bata under Toalla")
    void bathRobes(String nombre) {
        assertThat(subs.resolver(nombre, "Toalla")).isEqualTo("Bata");
        assertThat(subs.resolver("Toalla con Rombos", "Toalla")).isEmpty();
    }
}
