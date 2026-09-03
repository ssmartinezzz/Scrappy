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
 * The preferred brands are a SET, not an order: every one of them competes, and
 * price per gram decides between them. The previous contract picked the first
 * brand with stock and only compared value inside it, which meant the brands
 * further down the list could never win however good their price.
 */
@Epic("Outfit Orchestration")
@Feature("Supplements / Style")
@Story("Supplement value ranking")
@DisplayName("SupplementCombo — preferred brands compete on price per gram")
class SupplementComboMarcaPreferidaTest {

    private OutfitService outfitService;

    private static final Set<String> PROTEINA = Set.of("Proteína en Polvo");

    @BeforeEach
    void setUp() {
        outfitService = new OutfitService(new RecommendationService());
    }

    @Test
    @DisplayName("Any preferred brand can win — the cheapest per gram does")
    void entreMarcasPreferidasGanaElMejorPrecioPorGramo() {
        var ena   = suplemento("Whey ENA 1kg", 20000, "ENA");
        var star  = suplemento("Whey Star 1kg", 12000, "Star Nutrition");
        var gold  = suplemento("Whey Gold 1kg", 30000, "Gold Nutrition");

        assertThat(pick(List.of(ena, star, gold)).marca()).isEqualTo("Star Nutrition");
    }

    @Test
    @DisplayName("...and the same pool with a cheaper ENA flips to ENA")
    void elMismoPoolConEnaMasBarataEligeEna() {
        var ena   = suplemento("Whey ENA 2kg", 16000, "ENA");
        var star  = suplemento("Whey Star 1kg", 12000, "Star Nutrition");
        var gold  = suplemento("Whey Gold 1kg", 30000, "Gold Nutrition");

        assertThat(pick(List.of(ena, star, gold)).marca()).isEqualTo("ENA");
    }

    @Test
    @DisplayName("A preferred brand still beats an unlisted one, however cheap")
    void laMarcaPreferidaSigueGanandoleALaDesconocida() {
        var generica = suplemento("Whey Generica 5kg", 10000, "");
        var ena      = suplemento("Whey ENA 1kg", 20000, "ENA");

        assertThat(pick(List.of(generica, ena)).marca()).isEqualTo("ENA");
    }

    @Test
    @DisplayName("Syntha-6 is a product LINE, not a brand: Product.marca() says BSN")
    void syntha6EsPreferidaAunqueLaMarcaSeaBsn() {
        var generica = suplemento("Whey Generica 5kg", 10000, "");
        var syntha   = suplemento("BSN Syntha-6 Clasico Proteína 658g", 20000, "BSN");

        assertThat(pick(List.of(generica, syntha)).nombre())
                .isEqualTo("BSN Syntha-6 Clasico Proteína 658g");
    }

    @Test
    @DisplayName("Syntha-6 written with a space normalizes to the same token")
    void syntha6ConEspacioTambienMatchea() {
        var generica = suplemento("Whey Generica 5kg", 10000, "");
        var syntha   = suplemento("BSN Syntha 6 ISOLATE 907g", 20000, "");

        assertThat(pick(List.of(generica, syntha)).nombre()).isEqualTo("BSN Syntha 6 ISOLATE 907g");
    }

    @Test
    @DisplayName("Syntha-6 competes on price like everyone else, it is not a trump card")
    void syntha6CompitePorPrecioComoElResto() {
        var syntha = suplemento("BSN Syntha-6 907g", 30000, "BSN");
        var star   = suplemento("Whey Star 907g", 12000, "Star Nutrition");

        assertThat(pick(List.of(syntha, star)).marca()).isEqualTo("Star Nutrition");
    }

    @Test
    @DisplayName("BSA is gone: it matched zero catalog rows and was a typo for Syntha-6")
    void bsaYaNoEsMarcaPreferida() {
        var bsa      = suplemento("Whey BSA 1kg", 20000, "BSA");
        var generica = suplemento("Whey Generica 2kg", 10000, "");

        // Neither is preferred now, so value alone decides and the 5.000/kg wins.
        assertThat(pick(List.of(bsa, generica)).nombre()).isEqualTo("Whey Generica 2kg");
    }

    private OutfitService.SupplementPick pick(List<Product> catalogo) {
        List<OutfitService.SupplementPick> combo =
                outfitService.armarComboSuplementos(catalogo, 0, PROTEINA);
        assertThat(combo).hasSize(1);
        return combo.get(0);
    }

    private Product suplemento(String nombre, double precio, String marca) {
        return new Product("Sitio", nombre, precio, null,
                "https://test.com/" + nombre.replace(" ", "-"),
                "img.jpg", "Proteína", "", List.of(),
                new Product.MlScore(50, "", false, "estable", 50), marca);
    }
}
