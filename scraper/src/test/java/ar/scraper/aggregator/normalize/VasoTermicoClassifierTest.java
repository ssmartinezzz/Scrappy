package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Normalization")
@Feature("Category classification")
@Story("thermal tumblers vs plain cups")
@DisplayName("CategoryClassifier — a thermal tumbler is Termo y Botella, a plain cup stays Bazar")
class VasoTermicoClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    @ParameterizedTest(name = "[{index}] \"{0}\" -> Termo y Botella")
    @ValueSource(strings = {
        "Vaso Hydrate 473 Ml",
        "Vaso Hydrate Chop Cervecero 710 Ml",
        "Vaso Camelbak Tumbler 591 ml",
        "Vaso Camelbak Horizon 600 ml",
        "Vaso Hydrate 887 Ml",
        "Vaso Stanley Quencher Colección Oasis 591 ml",
        "Vaso Stanley Flip Straw Tumbler 887ml",
        "Vaso Stanley Quencher 887Ml",
        "Vaso Stanley Quencher New Frequency 1.18 lts.",
        "Vaso Stanley Quencher ProTour Flip Straw 1.18L",
        "Vaso Quencher ProTour Flip Straw Messi x Stanley 1913 1.18 Lts.",
        "Vaso Térmico Acero Inoxidable 500ml"
    })
    void thermalTumblers(String nombre) {
        assertThat(classifier.normalizarCategoria("", nombre)).isEqualTo("Termo y Botella");
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource(delimiterString = " => ", textBlock = """
        Vaso de Vidrio Base Verde 380 Ml Suka => Bazar
        Vaso Poliresina Negro 8,5X8,5X11 Cm Suka => Bazar
        Vaso Cafe 790ml Grooved PP Pokemon => Bazar
        Vaso Plástico - Central Perk => Bazar
        """)
    void plainCupsStayBazar(String nombre, String esperado) {
        assertThat(classifier.normalizarCategoria("", nombre)).isEqualTo(esperado);
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" -> not Termo y Botella")
    @ValueSource(strings = {"Porta Vaso Plástico Blanco Suka", "Camiseta Stanley Cup 2024"})
    void leadingNounWins(String nombre) {
        assertThat(classifier.normalizarCategoria("", nombre)).isNotEqualTo("Termo y Botella");
    }
}
