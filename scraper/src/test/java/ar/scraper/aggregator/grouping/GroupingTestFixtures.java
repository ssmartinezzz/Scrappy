package ar.scraper.aggregator.grouping;

import ar.scraper.model.Product;

import java.util.List;

/**
 * Shared test fixture helper for {@link ProductIdentityTest} and
 * {@link JaccardSimilarityTest}, hoisted from their byte-identical private
 * {@code product(sitio, nombre, marca, categoria, precio)} factories
 * (post-review cleanup, no behavior change). Distinct from
 * {@code GroupingServiceCharacterizationTest}'s 6-arg variant, which builds
 * URLs differently and is left untouched.
 */
final class GroupingTestFixtures {

    private GroupingTestFixtures() {}

    static Product product(String sitio, String nombre, String marca, String categoria, double precio) {
        return new Product(sitio, nombre, precio, null, "https://example.com/" + nombre, "",
                categoria, "", List.of(), Product.MlScore.EMPTY, marca);
    }
}
