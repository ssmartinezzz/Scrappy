package ar.scraper.model;

import ar.scraper.model.Product.MlScore;
import ar.scraper.model.Product.SenalCompra;
import ar.scraper.model.Product.SenalFinanciacion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code cantidadUnidades} component added to
 * {@link Product} as part of pack/combo pricing detection (PR 1).
 *
 * Covers: the canonical 17-arg constructor sets the field correctly,
 * every legacy overload defaults it to 1, and {@code esPack()} reflects it.
 */
class ProductTest {

    private static final List<String> TALLES = List.of("M");

    @Test
    void canonicalConstructorSetsCantidadUnidades() {
        Product p = new Product("Sitio", "Pack x3 Remeras", 15000, null,
                "http://x", "http://img", "Remera", "hombre", TALLES,
                MlScore.EMPTY, "Nike", "indumentaria", false, false,
                SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 3);

        assertThat(p.cantidadUnidades()).isEqualTo(3);
        assertThat(p.esPack()).isTrue();
    }

    @Test
    void canonicalConstructorWithQuantityOneIsNotPack() {
        Product p = new Product("Sitio", "Remera básica", 5000, null,
                "http://x", "http://img", "Remera", "hombre", TALLES,
                MlScore.EMPTY, "Nike", "indumentaria", false, false,
                SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1);

        assertThat(p.cantidadUnidades()).isEqualTo(1);
        assertThat(p.esPack()).isFalse();
    }

    @Test
    void legacyNineArgConstructorDefaultsToOne() {
        Product p = new Product("Sitio", "Remera", 5000, null,
                "http://x", "http://img", "Remera", "hombre", TALLES);

        assertThat(p.cantidadUnidades()).isEqualTo(1);
        assertThat(p.esPack()).isFalse();
    }

    @Test
    void legacyTenArgConstructorWithMlScoreDefaultsToOne() {
        Product p = new Product("Sitio", "Remera", 5000, null,
                "http://x", "http://img", "Remera", "hombre", TALLES, MlScore.EMPTY);

        assertThat(p.cantidadUnidades()).isEqualTo(1);
    }

    @Test
    void legacyElevenArgConstructorWithMarcaDefaultsToOne() {
        Product p = new Product("Sitio", "Remera", 5000, null,
                "http://x", "http://img", "Remera", "hombre", TALLES, MlScore.EMPTY, "Nike");

        assertThat(p.cantidadUnidades()).isEqualTo(1);
    }

    @Test
    void legacyThirteenArgConstructorWithRubroGymratDefaultsToOne() {
        Product p = new Product("Sitio", "Remera", 5000, null,
                "http://x", "http://img", "Remera", "hombre", TALLES, MlScore.EMPTY,
                "Nike", "indumentaria", true);

        assertThat(p.cantidadUnidades()).isEqualTo(1);
    }

    @Test
    void legacyFifteenArgConstructorWithSenalDefaultsToOne() {
        Product p = new Product("Sitio", "Remera", 5000, null,
                "http://x", "http://img", "Remera", "hombre", TALLES, MlScore.EMPTY,
                "Nike", "indumentaria", true, false, SenalCompra.EMPTY);

        assertThat(p.cantidadUnidades()).isEqualTo(1);
        assertThat(p.esPack()).isFalse();
    }

    @Test
    void esPackIsTrueOnlyWhenCantidadUnidadesGreaterThanOne() {
        Product pack = new Product("Sitio", "Combo x2", 20000, null,
                "http://x", "http://img", "Conjunto", "hombre", TALLES,
                MlScore.EMPTY, "Nike", "indumentaria", false, false,
                SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 2);
        Product single = new Product("Sitio", "Remera", 5000, null,
                "http://x", "http://img", "Remera", "hombre", TALLES,
                MlScore.EMPTY, "Nike", "indumentaria", false, false,
                SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1);

        assertThat(pack.esPack()).isTrue();
        assertThat(single.esPack()).isFalse();
    }
}
