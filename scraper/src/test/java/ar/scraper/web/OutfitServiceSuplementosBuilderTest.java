package ar.scraper.web;

import ar.scraper.model.Product;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Epic("Outfit Orchestration")
@Feature("Supplements / Style")
@Story("Suplementos builder")
@DisplayName("OutfitService — Suplementos builder")
class OutfitServiceSuplementosBuilderTest {

    private RecommendationService recommendationService;
    private OutfitService outfitService;

    @BeforeEach
    void setUp() {
        wireOutfitService();
    }

    @Step("Wire OutfitService with mocked RecommendationService")
    private void wireOutfitService() {
        recommendationService = mock(RecommendationService.class);
        outfitService = new OutfitService(recommendationService);
    }

    @Test
    void armarComboSuplementos_filtersToRequestedTypes() {
        var proteina = suplemento("Whey Protein 1kg", 5000);
        var creatina = suplemento("Creatina Monohidrato 300g", 3000);
        Allure.parameter("tipos", Set.of("Proteína en Polvo"));

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(proteina, creatina), 0, Set.of("Proteína en Polvo"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tipo()).isEqualTo("Proteína en Polvo");
    }

    @Test
    void armarComboSuplementos_returnsEmptyWhenNoProductsMatchType() {
        var creatina = suplemento("Creatina Monohidrato 300g", 3000);
        Allure.parameter("tipos", Set.of("Vitamina C"));

        // "Vitamina C" is now a subtype; creatina has no vitamin keywords → empty
        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(creatina), 0, Set.of("Vitamina C"));

        assertThat(result).isEmpty();
    }

    @Test
    void armarComboSuplementos_vitaminasKeywordMatching() {
        var vitamina = suplemento("Vitamina C 1000mg", 2000);
        Allure.parameter("tipos", Set.of("Vitamina C"));

        // "Vitaminas" is split — "Vitamina C 1000mg" matches the "Vitamina C" subtype
        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(vitamina), 0, Set.of("Vitamina C"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tipo()).isEqualTo("Vitamina C");
    }

    @Test
    void armarComboSuplementos_noTiposFilter_returnsAllMatchingTypes() {
        var proteina = suplemento("Whey Protein 1kg", 5000);
        var creatina = suplemento("Creatina Monohidrato 300g", 3000);
        var vitamina = suplemento("Vitamina C 1000mg", 2000);

        // null tipos → uses all SUPLEMENTO_SUBTIPOS (backward-compat)
        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(proteina, creatina, vitamina), 0, null);

        assertThat(result).extracting(OutfitService.SupplementPick::tipo)
                .containsExactlyInAnyOrder("Proteína en Polvo", "Creatina", "Vitamina C");
    }

    @Test
    void armarComboSuplementos_budgetConstraint_picksChampestWhenNoneAffordable() {
        var expensive = suplemento("Whey Protein 2kg", 2000);
        Allure.parameter("presupuesto", 1000);

        // presupuesto 1000 < precio 2000 → no candidate is affordable → fallback picks cheapest
        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(expensive), 1000, Set.of("Proteína en Polvo"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tipo()).isEqualTo("Proteína en Polvo");
        assertThat(result.get(0).precio()).isEqualTo(2000.0);
    }

    @Test
    void armarComboSuplementos_doesNotPickTwoOfSameType() {
        var whey1 = suplemento("Whey Protein 1kg", 5000);
        var whey2 = suplemento("Whey Isolate 1kg", 6000);
        var whey3 = suplemento("Protein Concentrate 2kg", 7000);

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(whey1, whey2, whey3), 0, Set.of("Proteína en Polvo"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tipo()).isEqualTo("Proteína en Polvo");
    }

    // ── suplementos-classification-fixes: GRANGER snacks + whitelist (Bug B/C) ──

    @Test
    void armarComboSuplementos_grangerCupcakeShowsAsSnackProteico() {
        var cupcake = producto("GRANGER Cupcake Vainilla", 3000, "Alimentos");

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(cupcake), 0, Set.of("Snack Proteico"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tipo()).isEqualTo("Snack Proteico");
    }

    @Test
    void armarComboSuplementos_grangerPuddingShowsAsSnackProteico() {
        var pudding = producto("GRANGER Chia Pudding 300g", 3500, "Alimentos");

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(pudding), 0, Set.of("Snack Proteico"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tipo()).isEqualTo("Snack Proteico");
    }

    @Test
    void armarComboSuplementos_omeletteShowsAsSnackProteico() {
        var omelette = producto("GRANGER Omelette Natural", 2800, "Alimentos");

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(omelette), 0, Set.of("Snack Proteico"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tipo()).isEqualTo("Snack Proteico");
    }

    @Test
    void armarComboSuplementos_snackProteicoCategoryReachesBuilder() {
        // Bug C: the classifier can canonically tag a product "Snack Proteico";
        // that category must be in the supplement whitelist or the product is
        // silently filtered out of the builder before subtype matching runs.
        var cookie = producto("Cookie Proteica ENA", 2500, "Snack Proteico");

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(cookie), 0, Set.of("Snack Proteico"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tipo()).isEqualTo("Snack Proteico");
    }

    // ── matcher: accents, word boundaries, single assignment ─────────────

    @Test
    void accentedNameMatchesUnaccentedKeyword() {
        // The classifier already strips accents before assigning categoria, so this
        // product is canonically "Proteína". The builder re-derived the subtype from
        // the raw name with a plain contains(), where the keyword "proteina" could
        // never match "proteína" — the product carries no whey/isolate token either,
        // so it fell out of the combo entirely.
        var proteina = suplemento("Proteína Isolada 1kg", 5000);

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(proteina), 0, Set.of("Proteína en Polvo"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nombre()).isEqualTo("Proteína Isolada 1kg");
    }

    @Test
    void keywordDoesNotMatchMidWord() {
        // "epa" (Omega 3) is a substring of "Preparado". Keywords must anchor at a
        // word boundary, the way SubcategoryResolver already space-pads its own.
        var batido = suplemento("Batido Preparado 500g", 4000);

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(batido), 0, Set.of("Omega 3"));

        assertThat(result).isEmpty();
    }

    @Test
    void keywordWithTrailingSpaceStaysWholeWord() {
        // "cla " is declared with a trailing space: whole word, not a prefix. It must
        // not turn "Clásico" into a fat burner.
        var clasico = suplemento("Ena Clásico 500g", 4000);

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(clasico), 0, Set.of("Quemador"));

        assertThat(result).isEmpty();
    }

    @Test
    void keywordStillMatchesSpanishPlural() {
        // Word-boundary anchoring must not cost recall: the keyword is "barrita" and
        // real listings say "Barritas". Anchoring is at the START of the token only.
        var barritas = suplemento("Barritas Proteicas x6", 4500);

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(barritas), 0, Set.of("Barra Proteica"));

        assertThat(result).hasSize(1);
    }

    @Test
    void productIsAssignedToExactlyOneSubtype() {
        // Each subtype used to filter the whole pool independently, so a name carrying
        // tokens of two subtypes was emitted twice — the same URL as both a powder and
        // a bar. The specific subtype must win and the generic one must not see it.
        var barra = suplemento("Barra de Proteína Whey", 2500);

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(barra), 0, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tipo()).isEqualTo("Barra Proteica");
    }

    // ── BCAA / Pre-Workout coverage ───────────────────────────────────────
    // Both categories were already in the supplement whitelist, so these products
    // reached the builder and then matched no subtype at all — permanently unpickable.

    @Test
    void bcaaIsPickable() {
        var bcaa = producto("BCAA 2:1:1 300g Limón", 8000, "BCAA");

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(bcaa), 0, Set.of("BCAA"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tipo()).isEqualTo("BCAA");
    }

    @Test
    void glutaminaCountsAsBcaa() {
        var glutamina = producto("Glutamina Micronizada 500g", 9000, "Suplemento");

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(glutamina), 0, Set.of("BCAA"));

        assertThat(result).hasSize(1);
    }

    @Test
    void preWorkoutIsPickable() {
        var pre = producto("Pre Workout Explosivo 300g", 12000, "Pre-Workout");

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(pre), 0, Set.of("Pre-Workout"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tipo()).isEqualTo("Pre-Workout");
    }

    @Test
    void gainerIsPickable() {
        // A gainer is now kept out of the protein pick by precedence rather than by a
        // veto, which also means it can be offered as what it actually is.
        var gainer = producto("Mass Gainer 3kg con 50g de proteína", 30000, "Gainer");

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(gainer), 0, Set.of("Gainer"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tipo()).isEqualTo("Gainer");
    }

    @Test
    void colagenoIsPickable() {
        var colageno = producto("Colágeno Hidrolizado 300g", 11000, "Colágeno");

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(colageno), 0, Set.of("Colágeno"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tipo()).isEqualTo("Colágeno");
    }

    @Test
    void wheyFortifiedWithCollagenIsStillProteinPowder() {
        // Colágeno sits BELOW "Proteína en Polvo" in precedence, unlike Gainer and
        // Pre-Workout: collagen-fortified whey exists and is a whey. Same reasoning that
        // kept KW_COLAGENO below KW_PROTEINA in the classifier.
        var whey = producto("Whey Protein con Colágeno 1kg", 24000, "Proteína");

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(whey), 0, Set.of("Proteína en Polvo"));

        assertThat(result).hasSize(1);
    }

    // ── "tiene proteína" ≠ "es proteína en polvo" ─────────────────────────

    @Test
    void massGainerIsNotOfferedAsProteinPowder() {
        // The one that hurts most: a gainer is mostly maltodextrin, so its price per
        // kilo is far below any whey — with value ranking it would win the protein
        // pick nearly every time.
        var gainer = producto("Mass Gainer 3kg con 50g de proteína", 30000, "Gainer");

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(gainer), 0, Set.of("Proteína en Polvo"));

        assertThat(result).isEmpty();
    }

    @Test
    void readyToDrinkProteinIsNotOfferedAsProteinPowder() {
        // Note the wording: "con Proteína" DOES hit the powder keyword, where "Proteica"
        // does not. A test using the latter would pass without exercising anything.
        var leche = producto("Leche con Proteína Vainilla 1L", 2500, "Proteína");

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(leche), 0, Set.of("Proteína en Polvo"));

        assertThat(result).isEmpty();
    }

    @Test
    void proteinYoghurtIsNotOfferedAsProteinPowder() {
        var yogur = producto("Yogur con Proteína Frutilla 200g", 1800, "Alimentos");

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(yogur), 0, Set.of("Proteína en Polvo"));

        assertThat(result).isEmpty();
    }

    @Test
    void preWorkoutMentioningProteinIsNotOfferedAsProteinPowder() {
        var pre = producto("Pre Workout con proteína 300g", 12000, "Pre-Workout");

        assertThat(outfitService.armarComboSuplementos(List.of(pre), 0, Set.of("Proteína en Polvo")))
                .isEmpty();
        assertThat(outfitService.armarComboSuplementos(List.of(pre), 0, Set.of("Pre-Workout")))
                .hasSize(1);
    }

    @Test
    void proteinFortifiedFoodIsNotProteinPowder() {
        // Generative form: enumerating "<food> con proteína" phrase by phrase leaks
        // forever, so the rule is compound — "con proteína" plus a food format noun.
        var avena    = producto("Avena Instantánea con Proteína 1kg", 6000, "Alimentos");
        var galletas = producto("Galletitas con Proteína x 6", 3000, "Alimentos");

        assertThat(outfitService.armarComboSuplementos(List.of(avena), 0, Set.of("Proteína en Polvo")))
                .isEmpty();
        assertThat(outfitService.armarComboSuplementos(List.of(galletas), 0, Set.of("Proteína en Polvo")))
                .isEmpty();
    }

    @Test
    void flavourNamesDoNotTripTheFormatVeto() {
        // The non-regression that constrains the veto list: flavour names borrow exactly
        // the nouns a format veto would reach for. This is why the veto is phrase-based
        // ("leche con proteina", "yogur proteico") and never a bare "leche" or "yogur".
        var chocolatada = producto("Whey Protein Sabor Leche Chocolatada 1kg", 22000, "Proteína");
        var yogurGriego = producto("Whey Protein Sabor Yogur Griego 1kg", 21000, "Proteína");

        assertThat(outfitService.armarComboSuplementos(List.of(chocolatada), 0, Set.of("Proteína en Polvo")))
                .hasSize(1);
        assertThat(outfitService.armarComboSuplementos(List.of(yogurGriego), 0, Set.of("Proteína en Polvo")))
                .hasSize(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Product suplemento(String nombre, double precio) {
        return producto(nombre, precio, "Suplemento");
    }

    private Product producto(String nombre, double precio, String categoria) {
        return new Product("Sitio", nombre, precio, null,
                "https://test.com/" + nombre.replace(" ", "-"),
                "img.jpg", categoria, "", List.of(), null, "Marca");
    }
}
