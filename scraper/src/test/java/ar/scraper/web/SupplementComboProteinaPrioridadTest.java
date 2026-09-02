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
 * Category preference inside the "Proteína en Polvo" pick, added once V32 split the
 * bucket into {@code Proteína Isolada} / {@code Proteína} / {@code Proteína Vegetal}.
 *
 * <p>The three categories are not interchangeable to a buyer, so the pick stopped
 * treating them as one pool: the isolate wins, plant protein is last resort.</p>
 */
@Epic("Outfit Orchestration")
@Feature("Supplements / Style")
@Story("Protein category preference")
@DisplayName("SupplementCombo — the pick prefers isolate and avoids plant protein")
class SupplementComboProteinaPrioridadTest {

    private OutfitService outfitService;

    private static final Set<String> PROTEINA = Set.of("Proteína en Polvo");

    @BeforeEach
    void setUp() {
        outfitService = new OutfitService(new RecommendationService());
    }

    @Test
    @DisplayName("The isolate wins even against a cheaper generic whey")
    void laIsoladaGanaSobreLaGenerica() {
        var generica = suplemento("Whey Generica 2kg", 10000, "", "Proteína");
        var isolada  = suplemento("Whey Isolate 1kg", 20000, "", "Proteína Isolada");

        assertThat(pick(List.of(generica, isolada)).nombre()).isEqualTo("Whey Isolate 1kg");
    }

    @Test
    @DisplayName("...and even against a preferred brand, because the category tier runs first")
    void laIsoladaGanaSobreLaMarcaPreferida() {
        var enaGenerica = suplemento("Whey ENA 1kg", 20000, "ENA", "Proteína");
        var isolada     = suplemento("Whey Isolate Generica 1kg", 20000, "", "Proteína Isolada");

        assertThat(pick(List.of(enaGenerica, isolada)).nombre())
                .isEqualTo("Whey Isolate Generica 1kg");
    }

    @Test
    @DisplayName("Brand still ranks WITHIN the winning category")
    void laMarcaDesempataAdentroDeLaIsolada() {
        var isoladaGenerica = suplemento("Whey Isolate Generica 2kg", 10000, "", "Proteína Isolada");
        var isoladaEna      = suplemento("Whey Isolate ENA 1kg", 20000, "ENA", "Proteína Isolada");

        assertThat(pick(List.of(isoladaGenerica, isoladaEna)).marca()).isEqualTo("ENA");
    }

    @Test
    @DisplayName("Plant protein loses to anything else, however good its value")
    void laVegetalPierdeContraCualquierOtra() {
        var vegetal  = suplemento("Proteina Vegetal Barata 3kg", 9000, "ENA", "Proteína Vegetal");
        var generica = suplemento("Whey Cara 1kg", 40000, "", "Proteína");

        assertThat(pick(List.of(vegetal, generica)).nombre()).isEqualTo("Whey Cara 1kg");
    }

    @Test
    @DisplayName("...but it is a de-preference, not a veto: a vegetal-only pool still picks")
    void laVegetalSeEligeSiEsLoUnicoQueHay() {
        var vegetal = suplemento("Proteina Vegetal 1kg", 12000, "", "Proteína Vegetal");

        assertThat(pick(List.of(vegetal)).nombre()).isEqualTo("Proteina Vegetal 1kg");
    }

    @Test
    @DisplayName("BSN is a real brand with 24 catalog rows; BSA matched zero")
    void bsnEsUnaMarcaPreferida() {
        var generica = suplemento("Whey Generica 2kg", 10000, "", "Proteína");
        var bsn      = suplemento("Whey BSN 1kg", 20000, "BSN", "Proteína");

        assertThat(pick(List.of(generica, bsn)).marca()).isEqualTo("BSN");
    }

    @Test
    @DisplayName("The category tier is a no-op for subtypes that have no protein category")
    void elTierDeCategoriaNoAfectaOtrosSubtipos() {
        var cara   = suplemento("Creatina 100 g", 6000, "", "Creatina");
        var barata = suplemento("Creatina 300 g", 9000, "", "Creatina");

        List<OutfitService.SupplementPick> combo =
                outfitService.armarComboSuplementos(List.of(cara, barata), 0, Set.of("Creatina"));

        assertThat(combo).hasSize(1);
        assertThat(combo.get(0).nombre()).isEqualTo("Creatina 300 g");
    }

    private OutfitService.SupplementPick pick(List<Product> catalogo) {
        List<OutfitService.SupplementPick> combo =
                outfitService.armarComboSuplementos(catalogo, 0, PROTEINA);
        assertThat(combo).hasSize(1);
        return combo.get(0);
    }

    private Product suplemento(String nombre, double precio, String marca, String categoria) {
        return new Product("Sitio", nombre, precio, null,
                "https://test.com/" + nombre.replace(" ", "-"),
                "img.jpg", categoria, "", List.of(),
                new Product.MlScore(50, "", false, "estable", 50), marca);
    }
}
