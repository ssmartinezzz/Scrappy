package ar.scraper.outfits;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Regenerar" kept showing Syntha-6.
 *
 * <p>BSN is a hard filter, and regenerating only excluded the URLs already seen: with
 * several BSN tubs in the catalog (sizes, flavours, sites), every click landed on
 * another BSN and ENA, Star and Gold never showed up. Rotation is by brand now — BSN
 * first, then the unseen brand with the best $/g.</p>
 */
@Epic("Outfit Orchestration")
@Feature("Supplement combo")
@Story("Regenerate")
@DisplayName("SupplementCombo — regenerating rotates brands, not just URLs")
class SupplementComboRotacionDeMarcaTest {

    private final SupplementCombo combo = new SupplementCombo(new RecommendationService());

    private static Product whey(String marca, String nombre, double precio) {
        return new Product("entreno", nombre, precio, null,
                "https://test/" + nombre.replace(" ", "-"), "https://img/x.jpg",
                "Proteína", "unisex", List.of(), Product.MlScore.EMPTY, marca, "suplementos", false);
    }

    private static Product creatina(String marca, String nombre, double precio) {
        return new Product("entreno", nombre, precio, null,
                "https://test/" + nombre.replace(" ", "-"), "https://img/x.jpg",
                "Creatina", "unisex", List.of(), Product.MlScore.EMPTY, marca, "suplementos", false);
    }

    private final List<Product> proteinas = List.of(
            whey("BSN", "BSN Syntha-6 907g", 60000),
            whey("BSN", "BSN Syntha-6 Isolate 907g", 70000),
            whey("BSN", "BSN Syntha-6 2270g", 140000),
            whey("ENA", "Whey ENA 1kg", 30000),              // 30/g
            whey("Star Nutrition", "Whey Star 1kg", 20000),  // 20/g
            whey("Gold Nutrition", "Whey Gold 1kg", 25000)); // 25/g

    /** Marcas mostradas en {@code clicks} requests seguidos, como hace el panel. */
    private List<String> marcasEnSecuencia(List<Product> catalogo, String tipo, int clicks) {
        List<String> vistas = new ArrayList<>();
        List<String> marcas = new ArrayList<>();
        for (int i = 0; i < clicks; i++) {
            OutfitService.SupplementPick pick = combo.armarComboSuplementos(
                    catalogo, 0, Set.of(tipo), Set.copyOf(vistas)).get(0);
            vistas.add(pick.url());
            marcas.add(pick.marca());
        }
        return marcas;
    }

    @Test
    @DisplayName("the first pick is still BSN")
    void elPrimerPickSigueSiendoBsn() {
        assertThat(marcasEnSecuencia(proteinas, "Proteína en Polvo", 1)).containsExactly("BSN");
    }

    @Test
    @DisplayName("after BSN, the unseen brands follow in $/g order, not another BSN")
    void despuesDeBsnSiguenLasOtrasMarcasPorPrecioPorGramo() {
        assertThat(marcasEnSecuencia(proteinas, "Proteína en Polvo", 4))
                .containsExactly("BSN", "Star Nutrition", "Gold Nutrition", "ENA");
    }

    @Test
    @DisplayName("once every brand was shown, the cycle restarts at BSN with a new product")
    void alAgotarLasMarcasVuelveABsnConOtroProducto() {
        List<String> vistas = new ArrayList<>();
        List<OutfitService.SupplementPick> picks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            OutfitService.SupplementPick pick = combo.armarComboSuplementos(
                    proteinas, 0, Set.of("Proteína en Polvo"), Set.copyOf(vistas)).get(0);
            vistas.add(pick.url());
            picks.add(pick);
        }

        assertThat(picks.get(4).marca()).isEqualTo("BSN");
        assertThat(picks.get(4).url()).isNotEqualTo(picks.get(0).url());
    }

    @Test
    @DisplayName("creatine rotates the same way")
    void laCreatinaRotaIgual() {
        List<Product> creatinas = List.of(
                creatina("BSN", "BSN Creatine 309g", 40000),
                creatina("BSN", "BSN Creatine 600g", 70000),
                creatina("ENA", "Creatina ENA 300g", 15000),
                creatina("Star Nutrition", "Creatina Star 300g", 18000),
                creatina("Gold Nutrition", "Creatina Gold 300g", 21000));

        assertThat(marcasEnSecuencia(creatinas, "Creatina", 4))
                .containsExactly("BSN", "ENA", "Star Nutrition", "Gold Nutrition");
    }

    @Test
    @DisplayName("a brand missing from the catalog is skipped, not waited for")
    void unaMarcaSinStockSeSaltea() {
        List<Product> sinGold = proteinas.stream()
                .filter(p -> !"Gold Nutrition".equals(p.marca()))
                .collect(Collectors.toList());

        assertThat(marcasEnSecuencia(sinGold, "Proteína en Polvo", 4))
                .containsExactly("BSN", "Star Nutrition", "ENA", "BSN");
    }
}
