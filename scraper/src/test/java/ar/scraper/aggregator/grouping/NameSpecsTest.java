package ar.scraper.aggregator.grouping;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Aggregation & Grouping")
@Feature("Grouping")
@Story("Name-derived specs")
@DisplayName("NameSpecs — extraction and compatibility")
class NameSpecsTest {

    private static Set<Double> num(String name, String key) {
        return NameSpecs.of(name).numeric.getOrDefault(key, Set.of());
    }

    private static Set<String> txt(String name, String key) {
        return NameSpecs.of(name).text.getOrDefault(key, Set.of());
    }

    private static boolean ok(String a, String b) {
        return NameSpecs.of(a).compatibleWith(NameSpecs.of(b));
    }

    @Test
    void thousandsSeparatorIsFolded() {
        assertThat(num("mouse 20.000 dpi", "dpi")).containsExactly(20000.0);
    }

    @Test
    void gluedInchesAreAUnitButSpacedInIsNot() {
        assertThat(num("monitor 5in", "pulg")).containsExactly(5.0);
        assertThat(num("convertible 2 in 1 5\"", "pulg")).containsExactly(5.0);
    }

    @Test
    void speedsAreNeitherSizeNorCapacity() {
        NameSpecs s = NameSpecs.of("ssd 6000mb/s");
        assertThat(s.text.getOrDefault("talla", Set.of())).isEmpty();
        assertThat(s.numeric).doesNotContainKeys("capmb", "cap");
    }

    @Test
    void multipliedCapacityNeedsNoSpaceAfterX() {
        assertThat(num("ventus 2x 8gb", "cap")).containsExactly(8.0);
        assertThat(num("ram 2x8gb", "cap")).containsExactly(16.0);
        assertThat(num("ram 16gb (2x8gb)", "cap")).containsExactlyInAnyOrder(16.0);
    }

    @Test
    void terabytesAreNormalisedAndM2IsIgnored() {
        assertThat(num("ssd m.2 1tb", "cap")).containsExactly(1024.0);
        assertThat(txt("ssd m.2 1tb", "cod2")).isEmpty();
        assertThat(txt("ssd m2 1tb", "cod2")).isEmpty();
    }

    @Test
    void weightsAndVolumesAreNormalised() {
        assertThat(num("creatina 1.5 kg", "peso")).containsExactly(1500.0);
        assertThat(num("whey 2 lb", "peso")).containsExactly(907.2);
        assertThat(num("botella 24 oz", "vol")).containsExactly(709.7);
        assertThat(num("mochila 25 l", "vol")).containsExactly(25000.0);
        assertThat(num("fuente 650w", "pot")).containsExactly(650.0);
        assertThat(num("creatina 300 caps", "uds")).containsExactly(300.0);
    }

    @Test
    void dimensionsNeedAUnit() {
        assertThat(txt("pad xl (900x400x3mm)", "dim")).containsExactly("400,900");
        assertThat(txt("pad 90x40cm", "dim")).containsExactly("400,900");
        assertThat(txt("kit 2x8", "dim")).isEmpty();
    }

    @Test
    void sizesFromLettersAndTalle() {
        assertThat(txt("pad antideslizante s", "talla")).containsExactly("s");
        assertThat(txt("remera 2xl", "talla")).containsExactly("xxl");
        assertThat(txt("zapatilla talle 42.5", "talle")).containsExactly("42.5");
        assertThat(txt("pad xl (900x400x3mm)", "talla")).containsExactly("xl");
    }

    @Test
    void modelNumbersShortCodesAndTier() {
        assertThat(txt("rtx 5060 ti 8gb", "num")).containsExactly("5060");
        assertThat(txt("rtx 5060 ti 8gb", "tier")).containsExactly("ti");
        assertThat(txt("rtx 5060 8gb", "tier")).containsExactly("-");
        assertThat(txt("mouse r1 pro", "cod2")).containsExactly("r1");
        assertThat(txt("gabinete 4x fan", "cod2")).isEmpty();
        assertThat(txt("gabinete fan x3", "cod2")).isEmpty();
    }

    @Test
    void compatibilityRules() {
        assertThat(ok("whey 2 lb", "whey 907 g")).isTrue();
        assertThat(ok("whey 2 lb", "whey 5 lb")).isFalse();
        assertThat(ok("ssd 1tb", "ssd 1024gb")).isTrue();
        assertThat(ok("ram 16gb 2x8gb", "ram 16gb")).isTrue();
        assertThat(ok("pad s", "pad xl")).isFalse();
        assertThat(ok("pad s", "pad")).isTrue();
        assertThat(ok("ssd 860", "ssd 860 2280")).isTrue();
        assertThat(ok("rtx 5060", "rtx 5080")).isFalse();
        assertThat(ok("mwe v2", "mwe v3")).isFalse();
        assertThat(ok("rtx 5060", "rtx 5060 ti")).isFalse();
        assertThat(ok("katar pro", "katar pro xt")).isTrue();
    }

    @Test
    void versionsNormaliseRomansDecimalsAndIgnoreNoise() {
        assertThat(NameSpecs.of("caven iii").versions).containsExactly("3");
        assertThat(NameSpecs.of("caven 2.0").versions).containsExactly("2");
        assertThat(NameSpecs.of("racer 4.5").versions).containsExactly("4.5");
        assertThat(NameSpecs.of("ssd 512gb 2.5\" sata 3.0 usb 3.2 ddr4").versions).isEmpty();
        assertThat(NameSpecs.of("nike talle 42 v2").versions).isEmpty();
        assertThat(NameSpecs.of("fuente 80 plus bronze 650w").versions).isEmpty();
    }

    @Test
    void versionsCompareBySubsetAndCrossCheckModelNumbers() {
        assertThat(ok("caven 2.0", "caven iii")).isFalse();
        assertThat(ok("caven 2", "caven 2 3")).isTrue();
        assertThat(ok("new balance 80", "new balance 370")).isFalse();
    }

    @Test
    void editionWordsMustBeEqualWhenBothStateThem() {
        assertThat(ok("vapor elite", "vapor club")).isFalse();
        assertThat(ok("vapor elite", "vapor")).isTrue();
        assertThat(ok("viper pro", "viper pro se")).isFalse();
    }

    @Test
    void ghzAndDoubleQuoteInchesAreUnits() {
        assertThat(num("ryzen 5.4ghz", "ghz")).containsExactly(5.4);
        assertThat(num("short 6\" ", "pulg")).containsExactly(6.0);
        assertThat(num("short 7''", "pulg")).containsExactly(7.0);
        assertThat(ok("short launch 6''", "short launch 7''")).isFalse();
    }

    @Test
    void soleComesFromTokensSynonymsAndTrailingIn() {
        assertThat(txt("goletto fg/mg", "suela")).containsExactlyInAnyOrder("fg", "mg");
        assertThat(txt("botin futsal", "suela")).containsExactly("ic");
        assertThat(txt("botin pasto artificial", "suela")).containsExactly("tf");
        assertThat(txt("botin multitapon", "suela")).containsExactly("tf");
        assertThat(txt("predator club in", "suela")).containsExactly("ic");
        assertThat(txt("short under armour launch 5\" 2 in 1", "suela")).isEmpty();
        assertThat(ok("copa fg", "copa tf")).isFalse();
        assertThat(ok("copa fg/mg", "copa fg")).isTrue();
    }
}
