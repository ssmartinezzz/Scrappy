package ar.scraper.aggregator.normalize;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SubcategoryResolverPerfumeTest {

    private final SubcategoryResolver resolver = new SubcategoryResolver();
    private final CategoryClassifier clasificador = new CategoryClassifier();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Perfume EQUUS JUNGLE EAU DE PARFUM 100ml|Eau de Parfum",
            "Perfume Mujer EDP 50 ml|Eau de Parfum",
            "Parfum Intenso 30ml|Eau de Parfum",
            "Eau de Toilette KVN1989 100ml|Eau de Toilette",
            "Perfume Hombre EDT 100ml|Eau de Toilette",
            "Body Splash Vainilla 200ml|Body Splash",
            "Body Mist Frutos Rojos|Body Splash",
            "Colonia Infantil Fresca 120ml|Colonia",
            "Eau de Cologne Citrus|Colonia",
            "Perfume VCP Gold Edition 50ml|''",
            "Frères Scent 250 ML|''"
    })
    void perfumeSubcategoryFromName(String nombre, String esperada) {
        assertThat(resolver.resolver(nombre, "Perfume")).isEqualTo("''".equals(esperada) ? "" : esperada);
    }

    @Test
    void eauDeParfumBeatsAToiletteMention() {
        assertThat(resolver.resolver("Eau de Parfum Intense (no es eau de toilette)", "Perfume"))
                .isEqualTo("Eau de Parfum");
    }

    @Test
    void aSportWordInAPerfumeNameDoesNotInventASport() {
        assertThat(resolver.resolver("Perfume Hockey Club 100ml", "Perfume")).isEmpty();
    }

    @Test
    void otherCategoriesAreNotTouchedByPerfumeKeywords() {
        assertThat(resolver.resolver("Remera Eau de Parfum", "Remera")).isEmpty();
    }

    @ParameterizedTest(name = "{0} is not a Perfume")
    @CsvSource(delimiter = '|', value = {
            "Antiparras de Buceo Base-X Splash Azul",
            "Splash And Play Inflable Tortuga Verde",
            "Teja Colonial 44 Cm 900 Un Ceramica Del Norte"
    })
    void splashAndColonialWordsDoNotMakeAPerfume(String nombre) {
        assertThat(clasificador.normalizarCategoria("", nombre)).isNotEqualTo("Perfume");
    }

    @ParameterizedTest(name = "{0} is a Perfume")
    @CsvSource(delimiter = '|', value = {
            "Body Splash Vainilla 200ml",
            "Colonia Infantil Fresca 120ml",
            "Eau de Toilette 32 100ML"
    })
    void realFragrancesStayPerfume(String nombre) {
        assertThat(clasificador.normalizarCategoria("", nombre)).isEqualTo("Perfume");
    }
}
