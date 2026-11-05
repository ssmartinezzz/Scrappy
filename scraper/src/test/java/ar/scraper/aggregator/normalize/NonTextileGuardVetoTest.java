package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Normalization")
@Feature("Category")
@Story("NonTextileGuard vetoes silently")
@DisplayName("NonTextileGuard — current vetoes on Easy names and what the classifier does with them")
class NonTextileGuardVetoTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    private String clasificar(String texto) throws Exception {
        Method m = CategoryClassifier.class.getDeclaredMethod("clasificar", String.class);
        m.setAccessible(true);
        return (String) m.invoke(classifier, texto);
    }

    @ParameterizedTest(name = "\"{0}\" is vetoed")
    @ValueSource(strings = {
            "Vanitory Colgante 52x48.5x36 Cm Melamina Blanco Alba Amube",
            "Dispenser Doble Jabón Líquido Blanco Crystal Rock",
            "Colgante Rennes 1 Luz E27 Negro Ferrolux",
            "Aro de Conexión para Inodoro Ideal",
            "Insecticida Gel Mata Cucarachas 12 Grs Somerset",
            "Arco de sierra 21x62x2 cm Roots",
            "Puff Arco 40x40x46 Cm Teddy Blanco M+Design",
            "Riel con Bastón Metal Gris 2,80 Mts Nacional Perfiltec",
            "Cadena para Nieve Antideslizante Plástico Naranja 10 Un IAEL",
            "Set de Asado 18 Piezas con Maletín Beef Maker BBQ"
    })
    @DisplayName("names with a guard word among their first 35 characters are vetoed")
    void guardWordsInTheFirstCharactersVeto(String nombre) {
        assertThat(NonTextileGuard.esClaramenteNoTextil(nombre)).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" is not vetoed")
    @ValueSource(strings = {
            "Cemento 25 Kg Loma Negra",
            "Zapatillas Running adidas Adizero Pacer de Mujer",
            "Zapatillas Asics Gel-Kayano 30 Hombre",
            "Zapatillas Running Hombre Talle 42 Colgante",
            "Termotanque Eléctrico 90 Lts 1500 W Blanco Te90 Vesubio"
    })
    @DisplayName("a guard word beyond the first 35 characters, or glued to another word, does not veto")
    void everythingElseIsNotVetoed(String nombre) {
        assertThat(NonTextileGuard.esClaramenteNoTextil(nombre)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("blank text is never vetoed")
    void blankIsNotVetoed(String texto) {
        assertThat(NonTextileGuard.esClaramenteNoTextil(texto)).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\" -> empty string")
    @ValueSource(strings = {
            "Vanitory Colgante 52x48.5x36 Cm Melamina Blanco Alba Amube",
            "Dispenser Doble Jabón Líquido Blanco Crystal Rock",
            "Colgante Rennes 1 Luz E27 Negro Ferrolux",
            "Arco de sierra 21x62x2 cm Roots"
    })
    @DisplayName("a veto returns the empty string, exactly like a name that matched nothing")
    void vetoReturnsTheEmptyString(String nombre) throws Exception {
        assertThat(clasificar(nombre)).isEmpty();
        assertThat(clasificar("Producto sin ninguna palabra clave conocida")).isEmpty();
    }

    @ParameterizedTest(name = "\"{0}\" -> Otros")
    @ValueSource(strings = {
            "Vanitory Colgante 52x48.5x36 Cm Melamina Blanco Alba Amube",
            "Dispenser Doble Jabón Líquido Blanco Crystal Rock",
            "Colgante Rennes 1 Luz E27 Negro Ferrolux"
    })
    @DisplayName("a vetoed name with no tree, no raw and no weight ends in Otros")
    void vetoedNameWithoutAnythingElseIsOtros(String nombre) {
        assertThat(classifier.normalizarCategoria(null, nombre)).isEqualTo("Otros");
    }

    @ParameterizedTest(name = "\"{0}\" in a leaf mapped to {1}")
    @CsvSource(delimiter = '|', value = {
            "Vanitory Colgante 52x48.5x36 Cm Melamina Blanco Alba Amube | Vanitory",
            "Aro de Conexión para Inodoro Ideal | Sanitario",
            "Dispenser Doble Jabón Líquido Blanco Crystal Rock | Accesorio de Baño",
            "Colgante Rennes 1 Luz E27 Negro Ferrolux | Iluminación",
            "Arco de sierra 21x62x2 cm Roots | Herramienta Manual"
    })
    @DisplayName("a vetoed name in a mapped leaf gets the tree category")
    void vetoedNameInMappedLeafGetsTheTreeCategory(String nombre, String categoriaDelArbol) {
        assertThat(classifier.normalizarCategoria(null, nombre, categoriaDelArbol)).isEqualTo(categoriaDelArbol);
    }

    @Test
    @DisplayName("the leisure families run before the guard, so a guard word does not veto them")
    void leisureFamiliesAreNotVetoed() throws Exception {
        assertThat(NonTextileGuard.esClaramenteNoTextil("Aro de Básquetbol 90X60X43 Cm Radost")).isTrue();
        assertThat(clasificar("Aro de Básquetbol 90X60X43 Cm Radost")).isEqualTo("Equipamiento Deportivo");
    }
}
