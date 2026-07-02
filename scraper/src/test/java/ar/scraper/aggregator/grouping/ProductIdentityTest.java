package ar.scraper.aggregator.grouping;

import ar.scraper.model.Product;
import org.junit.jupiter.api.Test;

import static ar.scraper.aggregator.grouping.GroupingTestFixtures.product;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProductIdentity#calcularIdentidad(Product)},
 * extracted from {@code GroupingService.calcularIdentidad} (Work Unit 2 of
 * the aggregator SOLID modularization).
 */
class ProductIdentityTest {

    private final ProductIdentity identity = new ProductIdentity();

    @Test
    void samePrefixSameCategoryYieldsSameIdentityKey() {
        Product a = product("VCP", "Nike Air Force 1 Blanco Talle 42", "Nike", "Zapatilla Urbana", 100000);
        Product b = product("Freres", "Nike Air Force 1 Negro", "Nike", "Zapatilla Urbana", 90000);

        assertThat(identity.calcularIdentidad(a)).isEqualTo(identity.calcularIdentidad(b));
    }

    @Test
    void differentCategoryYieldsDifferentIdentityKey() {
        Product a = product("VCP", "Nike Air Force 1", "Nike", "Zapatilla Urbana", 100000);
        Product b = product("VCP", "Nike Air Force 1", "Nike", "Sneaker", 100000);

        assertThat(identity.calcularIdentidad(a)).isNotEqualTo(identity.calcularIdentidad(b));
    }

    @Test
    void stopWordsAndSizeDigitsAreFilteredFromKey() {
        Product p = product("VCP", "Nike Air Force 1 Blanco Talle 42 Hombre", "Nike", "Zapatilla Urbana", 100000);

        String key = identity.calcularIdentidad(p);

        assertThat(key).doesNotContain("blanco").doesNotContain("hombre").doesNotContain("talle");
    }

    @Test
    void accentsInMarcaAndNombreAreNormalizedBeforeKeyComputation() {
        // categoria itself is NOT accent-stripped by calcularIdentidad (only
        // marca+nombre are) — keep it identical (and accent-free) across both
        // fixtures so this test isolates the marca/nombre normalization step.
        Product a = product("VCP", "Pantalón Cargo", "Adidás", "Pantalon", 50000);
        Product b = product("Freres", "Pantalon Cargo", "Adidas", "Pantalon", 50000);

        assertThat(identity.calcularIdentidad(a)).isEqualTo(identity.calcularIdentidad(b));
    }
}
