package ar.scraper.web;

import ar.scraper.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OutfitServiceSupplementBudgetTest {

    private final OutfitService service = new OutfitService(new RecommendationService());

    private Product suplemento(String nombre, double precio, String marca) {
        return new Product("TestSitio", nombre, precio, null,
                "https://test/" + nombre.replace(" ", "-"), "https://img/test.jpg",
                "Suplemento", "unisex", List.of(), Product.MlScore.EMPTY, marca, "suplementos",
                false);
    }

    @Test
    void noBudgetOverloadDelegatesToZeroBudget() {
        // ENA brand → deterministic selection; price=99999 must not be filtered when budget=0
        Product whey = suplemento("Whey Protein 1kg", 99999, "ENA");

        List<OutfitService.SupplementPick> noLimit = service.armarComboSuplementos(List.of(whey));
        List<OutfitService.SupplementPick> zeroBudget = service.armarComboSuplementos(List.of(whey), 0);

        assertThat(noLimit).isNotEmpty();
        assertThat(zeroBudget).isNotEmpty();
        assertThat(noLimit.get(0).precio()).isEqualTo(99999.0);
        assertThat(zeroBudget.get(0).precio()).isEqualTo(99999.0);
    }

    @Test
    void budgetZeroDoesNotFilterCandidates() {
        // ENA has brand priority; budget=0 must not filter expensive product
        Product cheap = suplemento("Whey Protein Basica 1kg", 500, "");
        Product expensive = suplemento("Whey Isolate Premium 2kg", 50000, "ENA");

        List<OutfitService.SupplementPick> combo = service.armarComboSuplementos(List.of(cheap, expensive), 0);

        assertThat(combo).hasSize(1);
        assertThat(combo.get(0).precio()).isEqualTo(50000.0);
    }

    @Test
    void budgetFiltersToAffordableCandidate() {
        // budget=2000; only the 1000-price product is affordable
        Product cheap = suplemento("Whey Protein Basica 1kg", 1000, "");
        Product expensive = suplemento("Whey Isolate Pro 2kg", 50000, "");

        List<OutfitService.SupplementPick> combo = service.armarComboSuplementos(List.of(cheap, expensive), 2000);

        assertThat(combo).hasSize(1);
        assertThat(combo.get(0).precio()).isLessThanOrEqualTo(2000.0);
    }

    @Test
    void budgetExhaustedPicksCheapestAvailable() {
        // Budget=2000; Proteina=3000 (no affordable → picks cheapest=3000, remaining=0).
        // Creatina=1000 (no affordable since remaining=0 → picks cheapest=1000 anyway).
        Product whey = suplemento("Whey Protein 1kg", 3000, "");
        Product creatina = suplemento("Creatina Monohidratada 300g", 1000, "");

        List<OutfitService.SupplementPick> combo = service.armarComboSuplementos(List.of(whey, creatina), 2000);

        assertThat(combo).hasSize(2);
        assertThat(combo.stream().map(OutfitService.SupplementPick::tipo))
                .containsExactlyInAnyOrder("Proteína", "Creatina");
    }

    @Test
    void budgetDecreasesAcrossSlots() {
        // Budget=3000; Proteina=2000 (fits, remaining=1000).
        // Creatina: 500 fits remaining, 1500 does not → 500 must be selected.
        Product whey = suplemento("Whey Protein 1kg", 2000, "");
        Product creatina500 = suplemento("Creatina Monohidratada 300g", 500, "");
        Product creatina1500 = suplemento("Creatine Monohydrate 500g", 1500, "");

        List<OutfitService.SupplementPick> combo = service.armarComboSuplementos(
                List.of(whey, creatina500, creatina1500), 3000);

        assertThat(combo).hasSize(2);
        OutfitService.SupplementPick creatinaPick = combo.stream()
                .filter(p -> "Creatina".equals(p.tipo()))
                .findFirst().orElseThrow();
        assertThat(creatinaPick.precio()).isEqualTo(500.0);
    }

    @Test
    void noCandidatesForSubtipoSkipsSlot() {
        // Only a Proteína product — no Creatina/Quemador/Magnesio candidates.
        // The combo must have exactly 1 entry, not 4 with nulls.
        Product whey = suplemento("Whey Protein 1kg", 5000, "ENA");

        List<OutfitService.SupplementPick> combo = service.armarComboSuplementos(List.of(whey), 0);

        assertThat(combo).hasSize(1);
        assertThat(combo.get(0).tipo()).isEqualTo("Proteína");
    }
}
