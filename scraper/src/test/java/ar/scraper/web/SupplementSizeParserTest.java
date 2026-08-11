package ar.scraper.web;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Outfit Orchestration")
@Feature("Supplements / Style")
@Story("Supplement size parsing")
@DisplayName("SupplementSizeParser — package size from the product name")
class SupplementSizeParserTest {

    @Test
    void parsesKilos() {
        var t = SupplementSizeParser.parse("Whey Protein 1kg Vainilla");

        assertThat(t.familia()).isEqualTo(SupplementSizeParser.Familia.MASA);
        assertThat(t.magnitud()).isEqualTo(1000.0);
    }

    @Test
    void parsesGramsSoTheyCompareWithKilos() {
        var gramos = SupplementSizeParser.parse("Whey Protein 908 gr");
        var kilos  = SupplementSizeParser.parse("Whey Protein 1 kg");

        assertThat(gramos.familia()).isEqualTo(kilos.familia());
        assertThat(gramos.magnitud()).isEqualTo(908.0);
        assertThat(kilos.magnitud()).isEqualTo(1000.0);
    }

    @Test
    void parsesPounds() {
        var t = SupplementSizeParser.parse("Whey Protein 2 lb");

        assertThat(t.familia()).isEqualTo(SupplementSizeParser.Familia.MASA);
        assertThat(t.magnitud()).isCloseTo(907.18, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void parsesCommaAsDecimalSeparator() {
        var t = SupplementSizeParser.parse("Proteína 1,5 kg");

        assertThat(t.magnitud()).isEqualTo(1500.0);
    }

    @Test
    void parsesDotAsThousandsSeparator() {
        // es-AR writes 1000 grams as "1.000 g". Read as a decimal it would be 1 gram
        // and the product would look ~1000x more expensive per kilo than it is.
        var t = SupplementSizeParser.parse("Creatina 1.000 g");

        assertThat(t.magnitud()).isEqualTo(1000.0);
    }

    @Test
    void takesTheLargestMassWhenTheNameAlsoStatesAServing() {
        var t = SupplementSizeParser.parse("Whey 30g de proteína por porción - pote 1kg");

        assertThat(t.magnitud()).isEqualTo(1000.0);
    }

    @Test
    void countWinsOverMassBecauseMassIsThePerCapsuleDose() {
        // "1000mg" is the dose in each capsule, not the size of the jar. Reading it as
        // the package size makes a 60-capsule jar look like a 1-gram product.
        var t = SupplementSizeParser.parse("Vitamina C 1000mg x 60 cápsulas");

        assertThat(t.familia()).isEqualTo(SupplementSizeParser.Familia.CONTEO);
        assertThat(t.magnitud()).isEqualTo(60.0);
    }

    @Test
    void parsesVolume() {
        var t = SupplementSizeParser.parse("Aderezo Fit 500 ml");

        assertThat(t.familia()).isEqualTo(SupplementSizeParser.Familia.VOLUMEN);
        assertThat(t.magnitud()).isEqualTo(500.0);
    }

    @Test
    void reportsUnknownWhenTheNameCarriesNoSize() {
        var t = SupplementSizeParser.parse("Mayonesa Fit");

        assertThat(t.familia()).isEqualTo(SupplementSizeParser.Familia.DESCONOCIDA);
        assertThat(t.conocido()).isFalse();
    }

    @Test
    void doesNotInventASizeFromABareNumber() {
        var t = SupplementSizeParser.parse("Proteína 100% Whey");

        assertThat(t.conocido()).isFalse();
    }

    @Test
    void nullAndBlankAreUnknown() {
        assertThat(SupplementSizeParser.parse(null).conocido()).isFalse();
        assertThat(SupplementSizeParser.parse("   ").conocido()).isFalse();
    }
}
