package ar.scraper.aggregator.normalize;

import ar.scraper.classification.CategoryGroups;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TECH runs before the clothing block, so a hardware token later in a clothing name won:
 * "Gtx" (Gore-Tex) and "Arc" (Curry Arc) made GPUs, "Portanotebook" a Notebook, "Mother" a
 * Motherboard. 27 active rows on 2026-10-27, all with a clothing or sport noun first.
 * Names taken from the database.
 */
@Epic("Normalization")
@Feature("Category classification")
@Story("A leading clothing noun beats hardware tokens")
@DisplayName("CategoryClassifier — una prenda como sustantivo líder no es hardware")
class PrendaLiderBeforeTechTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Remera Under Armour Curry Arc                                         | Remera",
        "Remera Monitor                                                        | Remera",
        "Sweater Canguro Mother Negro                                          | Sweater",
        "Mochila Reebok Portanotebook                                          | Mochila",
        "Mochila Topper Laptop II                                              | Mochila",
        "Campera Dc Shoes Unisex Intel 30k Insulated Mujer Negras              | Campera",
        "Chaquetas de nieve Dc Shoes Unisex Intel 30k Insulated Hombre Blancas | Campera",
        "Pelotas De Padel adidas Speed Rx Tubo X 3 Unidades                    | Pelota",
        "Lonchera adidas Termico Cooler S                                      | Bolso",
        "MYPROTEIN Daily Vitamins Multi Vitamin 60 Tablets                     | Vitaminas",
    })
    void laPrendaGana(String nombre, String esperada) {
        assertThat(cat(nombre.trim())).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
        "Zapatillas adidas Terrex Free Hiker 2.0 Gtx |x",
        "Zapatillas Salomon X Ultra 360 Mid Gtx      |x",
        "Zapatillas Skechers Bobs Arc Waves 2        |x",
    })
    void unaZapatillaConGtxOArcEsCalzado(String nombre, String ignorado) {
        // Calzado, not one specific shoe: "Terrex Free Hiker" is a Borcego by KW_BORCEGO. Calzado is
        // also what keeps GymratTagger from tagging it.
        assertThat(CategoryGroups.esCalzado(cat(nombre.trim()))).as(cat(nombre.trim())).isTrue();
    }

    // Regression: the guard must not reach real hardware.
    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Placa de Video ASUS Dual GeForce RTX 4060 8GB  | GPU",
        "Placa De Video MSI Radeon RX 7600 Mech 2X 8GB  | GPU",
        "Cooler CPU Deepcool AK400                      | Cooler",
        "Tablet Samsung Galaxy Tab A9 64GB              | Tablet",
        "Notebook Lenovo IdeaPad Slim 3 15IAH8          | Notebook",
    })
    void elHardwareSigueSiendoHardware(String nombre, String esperada) {
        assertThat(cat(nombre.trim())).isEqualTo(esperada);
    }
}
