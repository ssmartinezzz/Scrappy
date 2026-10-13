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
@Story("Cross-site matching recall")
@DisplayName("GroupingService — recall without mixing models")
class GroupingMatchingRecallTest {

    private final GroupingService grouping = new GroupingService(new ProductIdentity(), new JaccardSimilarity());

    private List<ProductGroup> multi(Product... ps) {
        return grouping.agrupar(List.of(ps), true);
    }

    @Test
    void groupsAcrossSubcategoriesOfTheSameFamily() {
        Product vaypol = product("Vaypol", "Zapatillas adidas Response Runner 2", "adidas", "Zapatilla", 1);
        Product sporting = product("Sporting", "Zapatillas Running adidas Response Runner 2 Unisex", "Adidas", "Zapatilla Running", 1);

        assertThat(multi(vaypol, sporting)).hasSize(1);
    }

    @Test
    void groupsDespiteFillerWordsAndWordOrder() {
        Product vaypol = product("Vaypol", "Zapatillas adidas Streettalk", "adidas", "Zapatilla Urbana", 1);
        Product sporting = product("Sporting", "Zapatillas Urbanas adidas Streettalk de Hombre", "adidas", "Zapatilla Urbana", 1);

        assertThat(multi(vaypol, sporting)).hasSize(1);
    }

    @Test
    void doesNotGroupDifferentVersionNumbers() {
        Product vaypol = product("Vaypol", "Zapatillas adidas Supernova Rise 2", "adidas", "Zapatilla", 1);
        Product sporting = product("Sporting", "Zapatillas adidas Supernova Rise 3 De Hombre", "adidas", "Zapatilla", 1);

        assertThat(multi(vaypol, sporting)).isEmpty();
    }

    @Test
    void doesNotGroupDifferentModelCodes() {
        Product a = product("Fullh4rd", "MOTHER ASUS PRIME B860M-K DDR5 S1851", "", "Motherboard", 1);
        Product b = product("Armytech", "Mother Asus Prime B860m-a Ddr5 Lga 1851", "", "Motherboard", 1);

        assertThat(multi(a, b)).isEmpty();
    }

    @Test
    void seedAbsorbsOnlyTheBestMatchPerSite() {
        Product maximus = product("Maximus", "Placa de Video Gigabyte Nvidia Geforce RTX 5060 TI Eagle Max 8GB OC GDDR7", "Gigabyte", "GPU", 1);
        Product eagle = product("Compragamer", "Placa de Video Gigabyte Nvidia Geforce RTX 5060 Ti 8GB GDDR7 EAGLE MAX OC", "Gigabyte", "GPU", 1);
        Product windforce = product("Compragamer", "Placa de Video Gigabyte Nvidia Geforce RTX 5060 Ti 8GB GDDR7 WINDFORCE OC", "Gigabyte", "GPU", 1);
        Product gaming = product("Compragamer", "Placa de Video Gigabyte Nvidia Geforce RTX 5060 8GB GDDR7 Gaming OC", "Gigabyte", "GPU", 1);

        List<ProductGroup> grupos = multi(maximus, eagle, windforce, gaming);

        assertThat(grupos).hasSize(1);
        assertThat(grupos.get(0).size()).isEqualTo(2);
        assertThat(grupos.get(0).getNombre()).doesNotContain("WINDFORCE").doesNotContain("Gaming");
    }

    @Test
    void identicalNamesOnTheSameSiteAreKeptTogetherAsTies() {
        Product city = product("City", "Zapatillas Puma Court Lally", "Puma", "Zapatilla", 1);
        Product v1 = product("Vaypol", "Zapatillas Puma Court Lally", "Puma", "Zapatilla", 2);
        Product v2 = product("Vaypol", "Zapatillas Puma Court Lally", "Puma", "Zapatilla", 3);

        List<ProductGroup> grupos = multi(city, v1, v2);

        assertThat(grupos).hasSize(1);
        assertThat(grupos.get(0).size()).isEqualTo(3);
    }

    @Test
    void doesNotGroupDifferentBrands() {
        Product a = product("A", "Zapatillas Court Lally", "Nike", "Zapatilla", 1);
        Product b = product("B", "Zapatillas Court Lally", "Puma", "Zapatilla", 1);

        assertThat(multi(a, b)).isEmpty();
    }

    @Test
    void doesNotMixKidsWithAdults() {
        Product adult = product("Vaypol", "Zapatillas adidas Streettalk", "adidas", "Zapatilla Urbana", 1);
        Product kids = product("Sporting", "Zapatillas Urbanas adidas Streettalk de Niños", "adidas", "Zapatilla Urbana", 1);

        assertThat(multi(adult, kids)).isEmpty();
    }

    @Test
    void talleIsNotAVersionNumber() {
        Product a = product("VCP", "Nike Air Force 1 Blanco Talle 42", "Nike", "Zapatilla Urbana", 1);
        Product b = product("Freres", "Nike Air Force 1 Negro", "Nike", "Zapatilla Urbana", 1);

        assertThat(multi(a, b)).hasSize(1);
    }
}
