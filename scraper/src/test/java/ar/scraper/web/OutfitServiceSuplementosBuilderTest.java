package ar.scraper.web;

import ar.scraper.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OutfitServiceSuplementosBuilderTest {

    private RecommendationService recommendationService;
    private OutfitService outfitService;

    @BeforeEach
    void setUp() {
        recommendationService = mock(RecommendationService.class);
        outfitService = new OutfitService(recommendationService);
    }

    @Test
    void armarComboSuplementos_filtersToRequestedTypes() {
        var proteina = suplemento("Whey Protein 1kg", 5000);
        var creatina = suplemento("Creatina Monohidrato 300g", 3000);

        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(proteina, creatina), 0, Set.of("Proteína en Polvo"));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tipo()).isEqualTo("Proteína en Polvo");
    }

    @Test
    void armarComboSuplementos_returnsEmptyWhenNoProductsMatchType() {
        var creatina = suplemento("Creatina Monohidrato 300g", 3000);

        // "Vitamina C" is now a subtype; creatina has no vitamin keywords → empty
        List<OutfitService.SupplementPick> result =
                outfitService.armarComboSuplementos(List.of(creatina), 0, Set.of("Vitamina C"));

        assertThat(result).isEmpty();
    }

    @Test
    void armarComboSuplementos_vitaminasKeywordMatching() {
        var vitamina = suplemento("Vitamina C 1000mg", 2000);

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

    // ── helpers ──────────────────────────────────────────────────────────

    private Product suplemento(String nombre, double precio) {
        return new Product("Sitio", nombre, precio, null,
                "https://test.com/" + nombre.replace(" ", "-"),
                "img.jpg", "Suplemento", "", List.of(), null, "Marca");
    }
}
