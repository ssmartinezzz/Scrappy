package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Normalization")
@Feature("Collection hints")
@DisplayName("Monkyforce collection hints resolve through the classifiers")
class MonkyforceCollectionHintsTest {

    private final CategoryClassifier classifier = new CategoryClassifier();
    private final GenderResolver gender = new GenderResolver();

    @Test
    @DisplayName("every raw categoria hint the page sends resolves to a closed-domain category")
    void rawHintsResolve() {
        assertThat(classifier.normalizarCategoria("Remera", "Never Give Up")).isEqualTo("Remera");
        assertThat(classifier.normalizarCategoria("Musculosa", "Never Give Up")).isEqualTo("Musculosa");
        assertThat(classifier.normalizarCategoria("Short", "Never Give Up")).isEqualTo("Short");
        assertThat(classifier.normalizarCategoria("Buzo", "Never Give Up")).isEqualTo("Buzo");
        assertThat(classifier.normalizarCategoria("Pantalón", "Never Give Up")).isEqualTo("Pantalón");
    }

    @Test
    @DisplayName("the name wins over the raw categoria hint")
    void nameBeatsHint() {
        assertThat(classifier.normalizarCategoria("Remera", "Calza Larga Soft")).isEqualTo("Calza");
    }

    @Test
    @DisplayName("raw genero fills in when the name has no signal")
    void rawGeneroWhenNameIsSilent() {
        assertThat(gender.resolver("mujer", "Soft Gear", "Remera")).isEqualTo("mujer");
        assertThat(gender.resolver("hombre", "Never Give Up", "Remera")).isEqualTo("hombre");
    }

    @Test
    @DisplayName("a women's garment name wins over a blank raw genero")
    void brasCruzadoIsWomen() {
        String cat = classifier.normalizarCategoria("", "Bras Cruzado");
        assertThat(cat).isEqualTo("Corpino");
        assertThat(gender.resolver("", "Bras Cruzado", cat)).isEqualTo("mujer");
    }
}
