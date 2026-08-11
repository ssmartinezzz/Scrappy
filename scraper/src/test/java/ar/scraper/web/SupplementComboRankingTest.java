package ar.scraper.web;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ranking of the pick inside one supplement subtype. Uses a REAL
 * {@link RecommendationService} — the tiebreak under test is its
 * {@code baseMlScore}, so a mock returning 0.0 for everything would assert nothing.
 */
@Epic("Outfit Orchestration")
@Feature("Supplements / Style")
@Story("Supplement value ranking")
@DisplayName("SupplementCombo — pick ranking by price per unit")
class SupplementComboRankingTest {

    private OutfitService outfitService;

    private static final Set<String> PROTEINA = Set.of("Proteína en Polvo");

    @BeforeEach
    void setUp() {
        outfitService = new OutfitService(new RecommendationService());
    }

    @Test
    void picksBestPricePerKilo() {
        // 10.000/kg vs 8.000/kg — the bigger tub is dearer in absolute terms and
        // still the better buy. Absolute price alone would pick the small one.
        var chico  = suplemento("Whey Chico 1kg", 10000, "");
        var grande = suplemento("Whey Grande 2kg", 16000, "");

        var pick = pick(List.of(chico, grande));

        assertThat(pick.nombre()).isEqualTo("Whey Grande 2kg");
    }

    @Test
    void comparesGramsAgainstKilos() {
        // 9.000/908g = 9,91/g vs 9.500/1000g = 9,50/g. Nearly the same sticker price,
        // opposite verdicts once normalized.
        var lb  = suplemento("Whey Importada 908 gr", 9000, "");
        var kg  = suplemento("Whey Nacional 1 kg", 9500, "");

        var pick = pick(List.of(lb, kg));

        assertThat(pick.nombre()).isEqualTo("Whey Nacional 1 kg");
    }

    @Test
    void preferredBrandStillOutranksValue() {
        // Deliberate: SUPLEMENTO_MARCA_PRIORIDAD is a user-confirmed preference, so it
        // stays the outermost key. Value ranks WITHIN the preferred brand, it does not
        // override it. Swapping the two keys is a one-line change if that ever flips.
        var ena      = suplemento("Whey ENA 1kg", 20000, "ENA");
        var generica = suplemento("Whey Generica 2kg", 10000, "");

        var pick = pick(List.of(ena, generica));

        assertThat(pick.marca()).isEqualTo("ENA");
    }

    @Test
    void preferredBrandsAreRankedAmongThemselves() {
        // The list is an ORDER, not a set: ENA over Gold Nutrition over Star Nutrition
        // over BSA over Xtrenght. Value only breaks ties inside the winning brand, so
        // the cheapest-per-gram jar here still loses for being the last brand listed.
        var xtrenght = suplemento("Whey Xtrenght 2kg", 10000, "Xtrenght");
        var bsa      = suplemento("Whey BSA 1kg", 20000, "BSA");
        var star     = suplemento("Whey Star 1kg", 20000, "Star Nutrition");
        var gold     = suplemento("Whey Gold 1kg", 20000, "Gold Nutrition");
        var ena      = suplemento("Whey ENA 1kg", 20000, "ENA");

        assertThat(pick(List.of(xtrenght, bsa, star, gold, ena)).marca()).isEqualTo("ENA");
        assertThat(pick(List.of(xtrenght, bsa, star, gold)).marca()).isEqualTo("Gold Nutrition");
        assertThat(pick(List.of(xtrenght, bsa, star)).marca()).isEqualTo("Star Nutrition");
        assertThat(pick(List.of(xtrenght, bsa)).marca()).isEqualTo("BSA");
        assertThat(pick(List.of(xtrenght)).marca()).isEqualTo("Xtrenght");
    }

    @Test
    void ranksByValueWithinThePreferredBrand() {
        var enaCara   = suplemento("Whey ENA Chica 1kg", 20000, "ENA");
        var enaBarata = suplemento("Whey ENA Grande 2kg", 30000, "ENA");

        var pick = pick(List.of(enaCara, enaBarata));

        assertThat(pick.nombre()).isEqualTo("Whey ENA Grande 2kg");
    }

    @Test
    void doesNotCompareAcrossUnitFamilies() {
        // $/gram against $/capsule is a meaningless comparison. The subtype's majority
        // unit family wins the comparison and the odd one out falls to the score
        // tiebreak — here two capsule jars outvote the powder, and 25/caps beats 33.
        var caps120 = suplemento("Creatina 120 caps", 3000, "");
        var caps60  = suplemento("Creatina 60 caps", 2000, "");
        var polvo   = suplemento("Creatina 300 g", 6000, "");

        List<OutfitService.SupplementPick> combo =
                outfitService.armarComboSuplementos(List.of(caps120, caps60, polvo), 0, Set.of("Creatina"));

        assertThat(combo).hasSize(1);
        assertThat(combo.get(0).nombre()).isEqualTo("Creatina 120 caps");
    }

    @Test
    void fallsBackToMlScoreWhenNoSizeIsParseable() {
        // Neither name carries a size, so there is nothing to normalize by. The old code
        // picked at random here; the score that already ranks the feed decides instead.
        var floja = suplemento("Whey Sin Datos Floja", 5000, "", 90);
        var buena = suplemento("Whey Sin Datos Buena", 5000, "", 10);

        var pick = pick(List.of(floja, buena));

        assertThat(pick.nombre()).isEqualTo("Whey Sin Datos Buena");
    }

    @Test
    void selectionIsDeterministicAcrossCalls() {
        // The regression that matters: the old fallback was ThreadLocalRandom, so the
        // same catalog produced a different pick on every request.
        var a = suplemento("Whey Alfa", 5000, "");
        var b = suplemento("Whey Beta", 5000, "");
        var c = suplemento("Whey Gamma", 5000, "");
        List<Product> catalogo = List.of(a, b, c);

        String primera = pick(catalogo).url();
        for (int i = 0; i < 20; i++) {
            assertThat(pick(catalogo).url()).isEqualTo(primera);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private OutfitService.SupplementPick pick(List<Product> catalogo) {
        List<OutfitService.SupplementPick> combo =
                outfitService.armarComboSuplementos(catalogo, 0, PROTEINA);
        assertThat(combo).hasSize(1);
        return combo.get(0);
    }

    private Product suplemento(String nombre, double precio, String marca) {
        return suplemento(nombre, precio, marca, 50);
    }

    private Product suplemento(String nombre, double precio, String marca, int scoreP) {
        return new Product("Sitio", nombre, precio, null,
                "https://test.com/" + nombre.replace(" ", "-"),
                "img.jpg", "Suplemento", "", List.of(),
                new Product.MlScore(scoreP, "", false, "estable", 50), marca);
    }
}
