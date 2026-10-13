package ar.scraper.aggregator.grouping;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static ar.scraper.aggregator.grouping.GroupingTestFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProductIdentity#calcularIdentidad(Product)},
 * extracted from {@code GroupingService.calcularIdentidad} (Work Unit 2 of
 * the aggregator SOLID modularization).
 */
@Epic("Aggregation & Grouping")
@Feature("Grouping")
@Story("Product identity")
@DisplayName("ProductIdentity — identity-key computation")
class ProductIdentityTest {

    private final ProductIdentity identity = new ProductIdentity();

    @Test
    void sameFamilyDifferentWordOrderAndFillerYieldsSameKey() {
        Product a = product("Vaypol", "Zapatillas adidas Streettalk", "adidas", "Zapatilla Urbana", 1);
        Product b = product("Sporting", "Zapatillas Urbanas adidas Streettalk de Hombre", "adidas", "Zapatilla Urbana", 1);

        assertThat(identity.calcularIdentidad(a)).isEqualTo(identity.calcularIdentidad(b));
    }

    @Test
    void subcategoriesOfTheSameFamilyShareTheKey() {
        Product a = product("A", "Nike Pegasus", "Nike", "Zapatilla Urbana", 1);
        Product b = product("B", "Nike Pegasus", "Nike", "Zapatilla Running", 1);

        assertThat(identity.calcularIdentidad(a)).isEqualTo(identity.calcularIdentidad(b)).isEqualTo("zapatilla");
    }

    @Test
    void differentFamiliesYieldDifferentKeys() {
        Product a = product("A", "Nike Air", "Nike", "Remera", 1);
        Product b = product("B", "Nike Air", "Nike", "Zapatilla", 1);

        assertThat(identity.calcularIdentidad(a)).isNotEqualTo(identity.calcularIdentidad(b));
    }

    @Test
    void familyIsAccentStrippedAndLettersOnly() {
        assertThat(identity.calcularIdentidad(product("A", "x", "m", "Proteína Isolada", 1))).isEqualTo("proteina");
        assertThat(identity.calcularIdentidad(product("A", "x", "m", "  Placa-de Video", 1))).isEqualTo("placade");
    }

    @Test
    void blankOrNullCategoryYieldsEmptyKey() {
        assertThat(identity.calcularIdentidad(product("A", "x", "m", "", 1))).isEmpty();
        assertThat(identity.calcularIdentidad(product("A", "x", "m", null, 1))).isEmpty();
    }
}
