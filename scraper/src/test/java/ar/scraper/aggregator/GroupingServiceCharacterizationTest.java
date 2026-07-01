package ar.scraper.aggregator;

import ar.scraper.aggregator.GroupingService.ProductGroup;
import ar.scraper.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests for {@link GroupingService#agrupar(List, boolean)}.
 *
 * <p>These tests snapshot the CURRENT grouping behavior BEFORE any structural
 * change to {@code GroupingService} (Strict TDD safety net — see spec
 * "Safety Net Before Modification"). They must stay green, unmodified in
 * substance, through the Work Unit 2 package split (extraction of
 * {@code ProductIdentity}, {@code JaccardSimilarity}, promoted top-level
 * {@code ProductGroup}); only the import statements change when the types
 * move from {@code ar.scraper.aggregator} to
 * {@code ar.scraper.aggregator.grouping}.</p>
 */
class GroupingServiceCharacterizationTest {

    private final GroupingService grouping = new GroupingService();

    /** Fixture builder: fills non-essential {@link Product} args with neutral defaults. */
    private static Product product(String sitio, String nombre, String marca, String categoria,
                                     double precio, String imagenUrl) {
        return new Product(sitio, nombre, precio, null,
                "https://" + sitio + "/" + nombre.toLowerCase().replace(" ", "-"), imagenUrl,
                categoria, "", List.of(), Product.MlScore.EMPTY, marca);
    }

    // ── (a) single-site product → passthrough group ────────────────────────

    @Test
    void singleSiteProductPassesThroughAsItsOwnGroup() {
        Product p = product("VCP", "Nike Air Force 1 Blanco", "Nike", "Zapatilla Urbana", 100000, "img1.jpg");

        List<ProductGroup> grupos = grouping.agrupar(List.of(p), false);

        assertThat(grupos).hasSize(1);
        assertThat(grupos.get(0).size()).isEqualTo(1);
        assertThat(grupos.get(0).sitiosDistintos()).isEqualTo(1);
        assertThat(grupos.get(0).getNombre()).isEqualTo("Nike Air Force 1 Blanco");
    }

    // ── (b) same model across ≥2 sites → grouped, canonical nombre = shortest ─

    @Test
    void sameModelAcrossMultipleSitesGroupsWithShortestCanonicalName() {
        Product vcp = product("VCP", "Nike Air Force 1 Blanco Talle 42", "Nike", "Zapatilla Urbana", 120000, "img-vcp.jpg");
        Product freres = product("Freres", "Nike Air Force 1 Negro", "Nike", "Zapatilla Urbana", 110000, "img-freres.jpg");
        Product sporting = product("Sporting", "Nike Air Force 1", "Nike", "Zapatilla Urbana", 105000, "img-sporting.jpg");

        List<ProductGroup> grupos = grouping.agrupar(List.of(vcp, freres, sporting), false);

        assertThat(grupos).hasSize(1);
        ProductGroup grupo = grupos.get(0);
        assertThat(grupo.size()).isEqualTo(3);
        assertThat(grupo.sitiosDistintos()).isEqualTo(3);
        // Shortest nombre among the three wins as canonical.
        assertThat(grupo.getNombre()).isEqualTo("Nike Air Force 1");
    }

    // ── (c) "Nike Air Force" vs "Nike Air Max" → NOT grouped (Jaccard 0.5 < 0.55) ─

    @Test
    void airForceAndAirMaxAreNotGroupedBecauseJaccardMisses() {
        Product airForce = product("VCP", "Nike Air Force", "Nike", "Zapatilla Urbana", 100000, "img1.jpg");
        Product airMax = product("Freres", "Nike Air Max", "Nike", "Zapatilla Running", 100000, "img2.jpg");

        List<ProductGroup> grupos = grouping.agrupar(List.of(airForce, airMax), false);

        assertThat(grupos).hasSize(2);
        assertThat(grupos).allSatisfy(g -> assertThat(g.size()).isEqualTo(1));
    }

    // ── (d) soloMultiSitio=true filters single-site groups ─────────────────

    @Test
    void soloMultiSitioFiltersOutSingleSiteGroups() {
        Product multi1 = product("VCP", "Adidas Superstar Blanca", "Adidas", "Zapatilla Urbana", 90000, "img1.jpg");
        Product multi2 = product("Freres", "Adidas Superstar Negra", "Adidas", "Zapatilla Urbana", 95000, "img2.jpg");
        Product single = product("Sporting", "Puma Suede Clasica", "Puma", "Zapatilla Urbana", 80000, "img3.jpg");

        List<ProductGroup> todos = grouping.agrupar(List.of(multi1, multi2, single), false);
        List<ProductGroup> soloMulti = grouping.agrupar(List.of(multi1, multi2, single), true);

        assertThat(todos).hasSize(2);
        assertThat(soloMulti).hasSize(1);
        assertThat(soloMulti.get(0).sitiosDistintos()).isEqualTo(2);
    }

    // ── (e) ordering by precioMinimo ascending ──────────────────────────────

    @Test
    void groupsAreSortedByMinimumPriceAscending() {
        Product caro = product("VCP", "Vans Old Skool Negras", "Vans", "Zapatilla Skate", 150000, "img1.jpg");
        Product barato = product("Freres", "Converse Chuck Taylor", "Converse", "Zapatilla Urbana", 50000, "img2.jpg");
        Product medio = product("Sporting", "Puma Suede Clasica", "Puma", "Zapatilla Urbana", 90000, "img3.jpg");

        List<ProductGroup> grupos = grouping.agrupar(List.of(caro, barato, medio), false);

        assertThat(grupos).hasSize(3);
        assertThat(grupos.get(0).precioMinimo()).isEqualTo(50000);
        assertThat(grupos.get(1).precioMinimo()).isEqualTo(90000);
        assertThat(grupos.get(2).precioMinimo()).isEqualTo(150000);
    }

    // ── (f) ahorroPct / precioMaximo math ───────────────────────────────────

    @Test
    void ahorroPctAndPrecioMaximoMatchExpectedFormula() {
        Product barato = product("VCP", "Nike Air Force 1 Blanco", "Nike", "Zapatilla Urbana", 80000, "img1.jpg");
        Product caro = product("Freres", "Nike Air Force 1 Negro", "Nike", "Zapatilla Urbana", 100000, "img2.jpg");

        List<ProductGroup> grupos = grouping.agrupar(List.of(barato, caro), false);

        assertThat(grupos).hasSize(1);
        ProductGroup grupo = grupos.get(0);
        assertThat(grupo.precioMinimo()).isEqualTo(80000);
        assertThat(grupo.precioMaximo()).isEqualTo(100000);
        // (100000 - 80000) / 100000 * 100 = 20.0
        assertThat(grupo.ahorroPct()).isEqualTo(20.0);
    }

    // ── (g) same-site duplicates never merged ───────────────────────────────

    @Test
    void sameSiteProductsAreNeverMergedEvenIfIdentical() {
        Product a = product("VCP", "Nike Air Force 1 Blanco", "Nike", "Zapatilla Urbana", 100000, "img1.jpg");
        Product b = product("VCP", "Nike Air Force 1 Blanco", "Nike", "Zapatilla Urbana", 100000, "img2.jpg");

        List<ProductGroup> grupos = grouping.agrupar(List.of(a, b), false);

        // Same identity key -> same pre-group -> Jaccard sub-grouping still
        // refuses to merge two products from the same site.
        assertThat(grupos).hasSize(2);
        assertThat(grupos).allSatisfy(g -> assertThat(g.sitiosDistintos()).isEqualTo(1));
    }
}
