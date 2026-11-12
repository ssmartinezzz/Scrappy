package ar.scraper.aggregator;

import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.aggregator.normalize.GenderResolver;
import ar.scraper.aggregator.normalize.GymratTagger;
import ar.scraper.aggregator.normalize.PackQuantityDetector;
import ar.scraper.aggregator.normalize.SizeNormalizer;
import ar.scraper.aggregator.normalize.SportResolver;
import ar.scraper.aggregator.normalize.SubcategoryResolver;
import ar.scraper.classification.BrandExtractor;
import ar.scraper.classification.RubroResolver;
import ar.scraper.classification.SiteRegistry;
import ar.scraper.classification.SiteRegistry.Sitio;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-site rules of NormalizerService added with the five fashion sites: a fixed gender for the whole catalogue
 * (GENERO_FIJO_POR_SITIO, equus -> hombre) and words that make a product Otros on one store only
 * (PALABRAS_OTROS_POR_SITIO). The toy store's fixed "infantil" is pinned by ToyLeafWinsOverNameTest.
 */
@Epic("Normalization")
@Feature("Per-site rules")
@DisplayName("NormalizerService — equus is men only; hamacas/combos (oneplaceworld) and tote (kingofthekongo) are Otros")
class NormalizerSiteRulesTest {

    private final NormalizerService normalizer = normalizer();

    private static NormalizerService normalizer() {
        SiteRegistry registry = SiteRegistry.forTesting(Map.of(
                "equus", new Sitio("Equus", "equus", "vtex", false, null, "config", true),
                "oneplaceworld", new Sitio("Oneplaceworld", "oneplaceworld", "tiendanube", false, null, "config", true),
                "kingofthekongo", new Sitio("Kingofthekongo", "kingofthekongo", "tiendanube", false, null, "config", true),
                "midway", new Sitio("Midway", "midway", "tiendanube", false, null, "config", false),
                "laspepas", new Sitio("Laspepas", "laspepas", "magento", false, null, "config", true),
                "ayrun", new Sitio("Ayrun", "ayrun", "tiendanube", false, null, "config", true)));
        return new NormalizerService(new PackQuantityDetector(), new CategoryClassifier(), new BrandExtractor(),
                new GenderResolver(), new SizeNormalizer(), new SubcategoryResolver(),
                new RubroResolver(registry), new GymratTagger(), new SportResolver(), registry,
                org.mockito.Mockito.mock(ar.scraper.catalog.SitioCategoriaPort.class));
    }

    private Product normalizar(String sitio, String nombre, String generoScrapeado) {
        Product p = Product.builder().sitio(sitio).nombre(nombre).precio(1000.0)
                .url("http://" + sitio + ".test/" + nombre.hashCode()).categoria("")
                .genero(generoScrapeado).talles(List.of("M")).build();
        return normalizer.normalizar(List.of(p)).get(0);
    }

    // ─── GENERO_FIJO_POR_SITIO ─────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Calza Mujer Deportiva",        // name says women
        "Vestido Floral Dama",          // feminine-coded category
        "Remera Lisa",                  // no signal at all
        "Tanga Encaje Negra",           // Bombacha is feminine-coded too
        "Buzo Niños Estampado",         // even a kids signal: the store sells men only
    })
    @DisplayName("every Equus product is hombre, whatever the name or the scraped tag says")
    void equusIsAlwaysHombre(String nombre) {
        assertThat(normalizar("Equus", nombre, "mujer").genero()).isEqualTo("hombre");
        assertThat(normalizar("Equus", nombre, "").genero()).isEqualTo("hombre");
    }

    @Test
    @DisplayName("negative: the fixed gender is Equus' only — the same names on another store resolve normally")
    void otherSitesAreNotFixed() {
        assertThat(normalizar("Midway", "Calza Mujer Deportiva", "").genero()).isEqualTo("mujer");
        assertThat(normalizar("Midway", "Tanga Encaje Negra", "").genero()).isEqualTo("mujer");
        assertThat(normalizar("Oneplaceworld", "Calza Mujer Deportiva", "").genero()).isEqualTo("mujer");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"Remera Lisa", "Pantalon Hombre Recto", "Cartera Mini", "Fragancia Ninfa"})
    @DisplayName("every Las Pepas product is mujer, whatever the name says")
    void laspepasIsMujer(String nombre) {
        assertThat(normalizar("Laspepas", nombre, "mujer").genero()).isEqualTo("mujer");
        assertThat(normalizar("Laspepas", nombre, "").genero()).isEqualTo("mujer");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"Musculosa Seamless Animal Rbrn (Rosa)", "Pack Full Pink", "Short Hombre Liso"})
    @DisplayName("every Ayrun product is mujer: its names carry no gender word")
    void ayrunIsMujer(String nombre) {
        assertThat(normalizar("Ayrun", nombre, "").genero()).isEqualTo("mujer");
        assertThat(normalizar("Ayrun", nombre, "unisex").genero()).isEqualTo("mujer");
    }

    @Test
    @DisplayName("Las Pepas' kids line keeps the infantil the page tagged; no other fixed-gender site does")
    void laspepasKidsLineStaysInfantil() {
        assertThat(normalizar("Laspepas", "Musculosa Otto", "infantil").genero()).isEqualTo("infantil");
        assertThat(normalizar("Equus", "Musculosa Otto", "infantil").genero()).isEqualTo("hombre");
    }

    // ─── PALABRAS_OTROS_POR_SITIO ──────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "Hamaca Paraguaya Viajera",
        "Hamacas Colgantes x2",
        "HAMACA DE VIAJE",              // upper case
        "Combo Remera Short",
        "Combos de Verano",
        "Pack Combo-Verano Hombre",     // punctuation glued to the word
        "Remera Lisa Combo",            // the word at the end
    })
    @DisplayName("oneplaceworld: hamaca(s) and combo(s) are Otros")
    void oneplaceworldWordsAreOtros(String nombre) {
        assertThat(normalizar("Oneplaceworld", nombre, "").categoria()).isEqualTo("Otros");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"Tote Bag Lona", "TOTE Estampada", "Bolso Tote Negro", "Tote"})
    @DisplayName("kingofthekongo: tote is Otros")
    void kongoToteIsOtros(String nombre) {
        assertThat(normalizar("Kingofthekongo", nombre, "").categoria()).isEqualTo("Otros");
    }

    @Test
    @DisplayName("negative: the words are per site — kongo's hamaca/combo and oneplaceworld's tote keep the name category")
    void wordsAreScopedToTheirSite() {
        assertThat(normalizar("Kingofthekongo", "Hamaca Paraguaya Viajera", "").categoria()).isNotEqualTo("Otros");
        assertThat(normalizar("Oneplaceworld", "Tote Bag Lona", "").categoria()).isNotEqualTo("Otros");
        assertThat(normalizar("Midway", "Hamaca Paraguaya Viajera", "").categoria()).isNotEqualTo("Otros");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"Remera Lisa Algodón", "Buzo Hamacado Oversize", "Short Combinado Negro", "Remera Totem"})
    @DisplayName("boundary: whole words only — a name that merely contains a word, or none, is not Otros")
    void wholeWordsOnly(String nombre) {
        assertThat(normalizar("Oneplaceworld", nombre, "").categoria()).isNotEqualTo("Otros");
        assertThat(normalizar("Kingofthekongo", nombre, "").categoria()).isNotEqualTo("Otros");
    }
}
