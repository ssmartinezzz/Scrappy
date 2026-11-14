package ar.scraper.aggregator.grouping;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ar.scraper.aggregator.grouping.GroupingTestFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

@Epic("Aggregation & Grouping")
@Feature("Grouping")
@Story("Brand from the name and ambiguous same-site ties")
@DisplayName("GroupingService — name brands and ambiguous ties")
class GroupingBrandAndTiesTest {

    private final GroupingService grouping = new GroupingService(new ProductIdentity(), new JaccardSimilarity());

    private List<ProductGroup> multi(Product... ps) {
        return grouping.agrupar(List.of(ps), true);
    }

    @Test
    void splitsDifferentBrandsStatedOnlyInTheName() {
        assertThat(multi(
                product("A", "Placa de Video ASUS T1 GeForce RTX 5060 Ti 8GB GDDR7 OC", "", "GPU", 1),
                product("B", "Placa de Video Gigabyte RTX 5060 Ti Eagle 8GB GDDR7 OC", "", "GPU", 1))).isEmpty();
        assertThat(multi(
                product("A", "Placa de Video Gigabyte GeForce RTX 5080 16GB GDDR7 GAMING OC", "", "GPU", 1),
                product("B", "Placa de Video MSI RTX 5080 Gaming Trio 16GB GDDR7 OC", "", "GPU", 1))).isEmpty();
    }

    @Test
    void keepsNameBrandsThatOverlap() {
        assertThat(multi(
                product("A", "TECLADO HP HYPERX ALLOY RISE 75% RGB 7G7A4AA", "", "Teclado", 1),
                product("B", "Teclado Gamer HyperX Alloy Rise 75% RGB", "", "Teclado", 1))).hasSize(1);
    }

    @Test
    void keepsPairWhenOnlyOneSideFillsMarca() {
        assertThat(multi(
                product("A", "Mouse Corsair Katar Pro", "Corsair", "Mouse", 1),
                product("B", "Mouse Corsair Katar Pro", "", "Mouse", 1))).hasSize(1);
    }

    @Test
    void ambiguousTieAbsorbsNobodyFromThatSite() {
        assertThat(multi(
                product("Sporting", "Zapatillas Topper Terre", "Topper", "Zapatilla", 1),
                product("Vaypol", "Zapatillas Topper Terre Mid", "Topper", "Zapatilla", 1),
                product("Vaypol", "Zapatillas Topper Terre Cork", "Topper", "Zapatilla", 1))).isEmpty();
    }

    @Test
    void colourVariantsStillAbsorbTogether() {
        List<ProductGroup> grupos = multi(
                product("Sporting", "Remera Nike Modelo Alfa", "Nike", "Remera", 1),
                product("Vaypol", "Remera Nike Modelo Alfa Negra", "Nike", "Remera", 1),
                product("Vaypol", "Remera Nike Modelo Alfa Blanca", "Nike", "Remera", 1));
        assertThat(grupos).hasSize(1);
        assertThat(grupos.get(0).size()).isEqualTo(3);
    }

    @Test
    void nearIdenticalTiesStillAbsorbTogether() {
        List<ProductGroup> grupos = multi(
                product("Gamerfactory", "Fuente Asus 1000W TUF Gaming 80 Plus Gold", "", "Fuente", 1),
                product("Venex", "Fuente ASUS 1000W 80 Plus Gold Evo TUF Gaming ATX 3.1 PCIe 5.1", "", "Fuente", 1),
                product("Venex", "Fuente Asus TUF Gaming 1000W 80 Plus Gold Modular ATX 3.1 PCIe 5.1 White", "", "Fuente", 1));
        assertThat(grupos).hasSize(1);
        assertThat(grupos.get(0).size()).isEqualTo(3);
    }
}
