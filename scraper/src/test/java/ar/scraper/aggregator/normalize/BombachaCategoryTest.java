package ar.scraper.aggregator.normalize;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Bombacha: women's bottom underwear. "tanga" used to be a Calzoncillos keyword (men's underwear). */
@Epic("Normalization")
@Feature("Category classification")
@DisplayName("Bombacha — classifier, veto, gender, subcategory and pack count")
class BombachaCategoryTest {

    private final CategoryClassifier classifier = new CategoryClassifier();
    private final GenderResolver gender = new GenderResolver();
    private final SubcategoryResolver subcategory = new SubcategoryResolver();
    private final PackQuantityDetector pack = new PackQuantityDetector();

    private String cat(String nombre) {
        return classifier.normalizarCategoria("", nombre);
    }

    // ─── KW_BOMBACHA ───────────────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Bombacha Algodón Mujer",          // bombacha
        "Bombachas Lisas Mujer",           // plural contains the stem
        "Panty Algodón Negro",             // " panty " at the start
        "Ropa Interior Panty",             // " panty " at the end
        "Panties de Encaje",                // " panties "
        "Culotte Sin Costura Mujer",       // culotte
        "Colaless Microfibra Negro",       // colaless
        "Cola Less Blanca",                // cola less
        "Vedetina Lisa",                   // vedetina
        "Tanga Encaje Negra",              // the keyword that moved out of Calzoncillos
        "TANGA CLASICA",                   // upper case
        "Tánga Brasilera Dama",            // accent stripped before matching
        "Tanga, Encaje Negro",             // punctuation glued to the word
        "Tangas Gruesas",                  // plural contains the stem
    })
    @DisplayName("each Bombacha keyword files the product as Bombacha")
    void bombachaKeywords(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Bombacha");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Bombacha Bikini Rayada",          // bikini
        "Tanga de Baño Mujer",             // bano (accent stripped)
        "Bombacha Malla Entera",           // malla
        "Tanga Playa Estampada",           // playa
        "Bombacha Swim Bottom",            // swim
        "Pantalón Culotte Lino",           // pantalon
        "Culotte Jean Tiro Alto",          // jean
        "Bombacha Bermuda Mujer",          // bermuda
        "Culotte Short Mujer",             // " short "
        "Bombacha de Campo Lisa",          // campo
        "Bombacha Gaucho Hombre",          // gaucho
        "Bombacha Gabardina Beige",        // gabardina
        "Bombacha Rural Reforzada",        // rural
    })
    @DisplayName("each veto word keeps swimwear, trousers and the gaucho bombacha out of Bombacha")
    void vetoWords(String nombre) {
        assertThat(cat(nombre)).isNotEqualTo("Bombacha");
        // "tanga" must not have stayed in KW_CALZONCILLO: a vetoed tanga would fall through to men's underwear.
        assertThat(cat(nombre)).isNotEqualTo("Calzoncillos");
    }

    @Test
    @DisplayName("a swimwear bottom stays Malla, trousers stay Pantalón")
    void vetoedNamesKeepTheirOwnCategory() {
        assertThat(cat("Bombacha de Bikini Rayada")).isEqualTo("Malla");
        assertThat(cat("Pantalón Culotte Lino")).isEqualTo("Pantalón");
    }

    // ─── KW_CALZONCILLO keeps the men's underwear ──────────────────────────

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Calzoncillo Algodón Hombre",
        "Boxer Algodón Hombre Negro",
        "Slip Clásico Hombre",
        "Brief Hombre Algodón",
        "Jockstrap Hombre",
        "Cueca Boxer Hombre",
    })
    @DisplayName("boxers, slips and briefs stay Calzoncillos")
    void calzoncillosStayCalzoncillos(String nombre) {
        assertThat(cat(nombre)).isEqualTo("Calzoncillos");
    }

    @Test
    @DisplayName("precedence: Bombacha is checked before Calzoncillos, so a women's 'boxer culotte' is Bombacha")
    void bombachaBeatsCalzoncillos() {
        assertThat(cat("Boxer Culotte Mujer")).isEqualTo("Bombacha");
        assertThat(cat("Boxer Algodón Mujer")).isEqualTo("Calzoncillos");
    }

    // ─── GenderResolver ────────────────────────────────────────────────────

    @Test
    @DisplayName("Bombacha is feminine-coded: no signal -> mujer, and a VTEX 'Hombre' tag does not leak in")
    void bombachaIsWomens() {
        assertThat(gender.resolver("", "Tanga Encaje Negra", "Bombacha")).isEqualTo("mujer");
        assertThat(gender.resolver("Hombre", "Tanga Encaje Negra", "Bombacha")).isEqualTo("mujer");
    }

    @Test
    @DisplayName("negative: Calzoncillos is not feminine-coded, so the same VTEX 'Hombre' tag still wins there")
    void calzoncillosIsNotFeminineCoded() {
        assertThat(gender.resolver("Hombre", "Boxer Algodón", "Calzoncillos")).isEqualTo("hombre");
        assertThat(gender.resolver("", "Boxer Algodón", "Calzoncillos")).isNotEqualTo("mujer");
    }

    @Test
    @DisplayName("precedence: a kids' signal still beats the feminine-coded category")
    void kidsBeatBombacha() {
        assertThat(gender.resolver("", "Bombacha Niña Algodón", "Bombacha")).isEqualTo("infantil");
    }

    // ─── SubcategoryResolver ───────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "Tanga Encaje Negra|tanga",
        "Colaless Microfibra|colaless",
        "Cola Less Blanca|colaless",
        "Vedetina Lisa|vedetina",
        "Culotte Sin Costura|culotte",
        "Culotte Tanga Mixta|tanga",           // first entry of the tier wins
    })
    @DisplayName("Bombacha subcategory comes from its own tier-1 list")
    void subcategories(String nombre, String esperado) {
        assertThat(subcategory.resolver(nombre, "Bombacha")).isEqualTo(esperado);
    }

    @Test
    @DisplayName("the Bombacha subcategories are not offered to other categories")
    void subcategoriesAreScopedToBombacha() {
        assertThat(subcategory.resolver("Tanga Encaje Negra", "Calzoncillos")).isNotEqualTo("tanga");
        assertThat(subcategory.resolver("Vedetina Lisa", "Remera")).isNotEqualTo("vedetina");
    }

    // ─── PackQuantityDetector ──────────────────────────────────────────────

    @Test
    @DisplayName("'3 bombachas' is a pack of 3; a single bombacha is not a pack")
    void bombachaPlural() {
        assertThat(pack.detectar("3 bombachas algodón", "Bombacha")).isEqualTo(3);
        assertThat(pack.detectar("Bombacha algodón", "Bombacha")).isEqualTo(1);
    }
}
