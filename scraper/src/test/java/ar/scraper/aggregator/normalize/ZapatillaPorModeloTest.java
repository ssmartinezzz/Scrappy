package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Zapatilla by model: shoe-only model lists, sport veto, 'entrenamiento'")
class ZapatillaPorModeloTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Zapatillas Vans Authentic Negra|Zapatilla Skate",
        "Zapatillas DC Shoes Manteca 4|Zapatilla Skate",
        "Zapatillas Asics Gel-Excite 9|Zapatilla Running",
        "Zapatillas Adidas Galaxy 6|Zapatilla Running",
        "Zapatillas Adidas Response Runner|Zapatilla Running",
        "Zapatillas Saucony Ride 17 Revolution|Zapatilla Running",
        "Zapatillas Puma Squat Pro|Zapatilla Entrenamiento",
        "Zapatillas de Entrenamiento Mujer Negra|Zapatilla Entrenamiento",
        "Zapatillas Adidas Grand Court 2.0|Zapatilla Urbana",
        "Zapatillas Puma Caven III|Zapatilla Urbana",
        "Zapatillas adidas Handball Spezial|Zapatilla Urbana",
        "Zapatillas Lacoste Carnaby|Zapatilla Urbana",
        "Zapatillas adidas Grand Court Alpha Sk8 Mujer|Zapatilla Urbana",
        "Zapatillas adidas Gc Alpha Sk8 Hombre|Zapatilla Urbana",
        "Zapatillas Vans U SK8 Hi|Zapatilla Skate",
    })
    void eachTypeFromItsModel(String nombre, String esperada) {
        assertThat(classifier.normalizarCategoria("Zapatilla", nombre)).isEqualTo(esperada);
    }

    @Test
    @DisplayName("skate is checked before running, running before training, training before urbana")
    void order() {
        assertThat(classifier.normalizarCategoria("Zapatilla", "Zapatillas Vans Gel-Excite")).isEqualTo("Zapatilla Skate");
        assertThat(classifier.normalizarCategoria("Zapatilla", "Zapatillas Gel-Squat")).isEqualTo("Zapatilla Running");
        assertThat(classifier.normalizarCategoria("Zapatilla", "Zapatillas Squat Suede")).isEqualTo("Zapatilla Entrenamiento");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "Zapatillas Adidas Galaxy 6 Tenis",
        "Zapatillas Asics Gel-Resolution Tennis",
        "Zapatillas Adidas Response Padel",
        "Zapatillas Puma Caven Basquet",
        "Zapatillas Vans Voley",
        "Zapatillas Handball Mujer",
        "Zapatillas Asics Gel-Excite Futsal",
    })
    @DisplayName("a sport word keeps the row a plain Zapatilla")
    void sportVeto(String nombre) {
        assertThat(classifier.normalizarCategoria("Zapatilla", nombre)).isEqualTo("Zapatilla");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Zapatillas Trekking Salomon Speedcross|Zapatilla Trekking",
        "Zapatillas de Senderismo Montagne|Zapatilla Trekking",
        "Zapatillas Adidas Terrex Trekking|Zapatilla Trekking",
        "Zapatillas Adidas Galaxy Outdoor|Zapatilla Trekking",
        "Zapatillas Adidas Terrex Swift R3 Gtx|Zapatilla Trekking",
        "Zapatillas Salomon X Ultra 360 Mid Gtx|Zapatilla Trekking",
        "Zapatillas Salomon X-Ultra 4|Zapatilla Trekking",
        "Zapatillas Columbia Newton Ridge|Zapatilla Trekking",
        "Zapatillas Merrell Moab 3|Zapatilla Trekking",
        "Zapatillas Hombre Outdoor Anylander|Zapatilla Trekking",
        "Zapatillas Skychaser Hombre|Zapatilla Trekking",
    })
    @DisplayName("Zapatilla Trekking: the store's own word, or a trekking model next to a shoe word")
    void trekking(String nombre, String esperada) {
        assertThat(classifier.normalizarCategoria("Zapatilla", nombre)).isEqualTo(esperada);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Zapatillas Nike Pegasus Trail 4 Gtx|Zapatilla Running",
        "Zapatillas Adidas Terrex Eastrail 2|Zapatilla Running",
        "Zapatillas Salomon Speedcross 6|Zapatilla Running",
        "Zapatillas Columbia Montrail Trail Running|Zapatilla Running",
        "Zapatillas Merrell Trail Glove|Zapatilla Running",
        "Zapatillas Adidas Galaxy 6|Zapatilla Running",
    })
    @DisplayName("a running or trail signal keeps the shoe in Zapatilla Running when only a model (not the explicit word) says trekking")
    void trailStaysRunning(String nombre, String esperada) {
        assertThat(classifier.normalizarCategoria("Zapatilla", nombre)).isEqualTo(esperada);
    }

    @Test
    @DisplayName("the explicit word wins over a running or trail model, and X-Ultra alone is still Running")
    void explicitWordWins() {
        assertThat(classifier.normalizarCategoria("Zapatilla", "Zapatillas Trekking Trail Salomon")).isEqualTo("Zapatilla Trekking");
        assertThat(classifier.normalizarCategoria("Zapatilla", "Salomon X-Ultra 4")).isEqualTo("Zapatilla Running");
    }

    @Test
    @DisplayName("a trekking word on a non-shoe stays what it was")
    void trekkingNonShoe() {
        for (String nombre : new String[] {"Mochila Trekking 60 L", "Baston Trekking Aluminio", "Pantalon Trekking Hombre",
                "Campera Columbia Outdoor", "Remera Merrell", "Medias Hiking"}) {
            assertThat(classifier.normalizarCategoria(null, nombre)).as(nombre).doesNotStartWith("Zapatilla");
        }
    }

    @Test
    @DisplayName("words are whole words and need a shoe word beside them")
    void wholeWordAndShoeOnly() {
        assertThat(classifier.normalizarCategoria("Zapatilla", "Zapatillas Adidas Galaxys 6")).isEqualTo("Zapatilla");
        assertThat(classifier.normalizarCategoria(null, "Samsung Galaxy S24 Ultra")).doesNotStartWith("Zapatilla");
        assertThat(classifier.normalizarCategoria(null, "Gel Hidratante Quest 200 ml")).doesNotStartWith("Zapatilla");
        assertThat(classifier.normalizarCategoria(null, "Remera Vans Revolution")).doesNotStartWith("Zapatilla");
        assertThat(classifier.normalizarCategoria(null, "Gorra DC Shoes Nitro")).doesNotStartWith("Zapatilla");
    }

    @Test
    @DisplayName("'entrenamiento' does not make a non-shoe a shoe")
    void entrenamientoAlone() {
        assertThat(classifier.normalizarCategoria(null, "Plan de Entrenamiento Funcional")).doesNotStartWith("Zapatilla");
        assertThat(classifier.normalizarCategoria(null, "Bolso de Entrenamiento")).doesNotStartWith("Zapatilla");
    }
}
