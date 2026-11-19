package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Names are real rows (Sportline, Vaypol, Harvey, Easy, Prestigio; dev catalog and scrapes of 2026-11-19). */
@Epic("Normalization")
@Feature("Category classification")
@DisplayName("Shared leaks: Kit, Knit, Lamp, Cookie/Maple, Set on non-garments and paint litres")
class SharedClassifierLeaksTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Pantalón adidas Kit  Mujer|Pantalón",
        "Musculosa adidas Kit Logo De Mujer|Musculosa",
        "Short Fila Knit Mujer|Short",
        "Remera Heavy Knit Black|Remera",
        "Buzo Nike Club Knit|Buzo",
        "Remera Snauwaert Lamp Hombre|Remera",
        "Remera adidas Cookie  Hombre|Remera",
        "Pantalón Under Armour Rival Fleece|Pantalón",
        "Medias Topper Pack X 3 Invisibles Mujer|Medias",
        "Short adidas Workout All Set Hombre|Short",
        "Remera Puma Flex Cargo  Hombre|Remera",
    })
    @DisplayName("a leading garment noun is the category: Kit, Knit, Lamp, Cookie, Fleece or Set are model or fabric")
    void leadingGarmentNounWins(String nombre, String esperada) {
        assertThat(classifier.normalizarCategoria("", nombre)).isEqualTo(esperada);
    }

    @Test
    @DisplayName("Maple Grove on a shoe is not a food")
    void mapleIsNotFoodOnAShoe() {
        assertThat(classifier.normalizarCategoria("", "Zapatillas Timberland Maple Grove  Hombre")).isNotEqualTo("Alimentos");
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Set Lijas para Taladro 127 Mm 5 Piezas Black & Decker|Accesorio de Herramienta",
        "Set X 2 Velas Votivas Green Lemon Aromas Spirit|Decoración",
        "Set de Espejos Hexagonales Dorado 20x23 Cm 3 Un M+Design|Espejo",
        "Set x4 Individuales Fibra Natural Lisos|Bazar",
    })
    @DisplayName("a Set of something that is not a garment is not Conjunto: the name abstains and the curated leaf decides")
    void setOfNonGarments(String nombre, String hoja) {
        assertThat(classifier.normalizarCategoria("", nombre)).isNotEqualTo("Conjunto");
        assertThat(classifier.normalizarCategoria("", nombre, hoja)).isEqualTo(hoja);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
        "Conjunto adidas Tiro  Hombre",
        "Conjunto Puma Poly Baseball  Hombre",
        "Conjunto Fiume Sport Tripack Godoy Cruz Kids",
        "Set de 3 remeras y 2 shorts Topper",
    })
    @DisplayName("real garment sets stay Conjunto")
    void garmentSetsStay(String nombre) {
        assertThat(classifier.normalizarCategoria("", nombre)).isEqualTo("Conjunto");
    }

    @Test
    @DisplayName("a trailing volume is food only when no leaf describes the row: a paint under 'Pinturas' abstains")
    void paintLitresUnderALeafAbstain() {
        assertThat(classifier.normalizarCategoria("Preparación de superficies", "Fondo para Galvanizado Super Galvite 0,5 L"))
                .isNotEqualTo("Alimentos");
        assertThat(classifier.normalizarCategoria("", "Producto sin nombre claro 500 g")).isEqualTo("Alimentos");
    }
}
