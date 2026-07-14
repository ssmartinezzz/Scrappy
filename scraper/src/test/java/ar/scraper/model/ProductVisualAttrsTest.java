package ar.scraper.model;

import ar.scraper.model.Product.MlScore;
import ar.scraper.model.Product.SenalCompra;
import ar.scraper.model.Product.SenalFinanciacion;
import ar.scraper.model.Product.VisualAttrs;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code VisualAttrs} nested record added to
 * {@link Product} as part of fashion image classification (PR1). Mirrors
 * the existing {@code MlScore} EMPTY-constant pattern: image-derived
 * attributes (fit/estampado/escote/color) are additive and fill-only —
 * this PR only wires the model/DB shape, nothing computes real values yet.
 */
@Epic("Domain Model")
@Feature("Product")
@DisplayName("Product — VisualAttrs (image classification attributes)")
class ProductVisualAttrsTest {

    private static final List<String> TALLES = List.of("M");

    @Test
    void visualAttrsConstructorSetsAllFields() {
        VisualAttrs visual = new VisualAttrs("oversize", "estampado", "cuello redondo", "azul");

        assertThat(visual.fit()).isEqualTo("oversize");
        assertThat(visual.estampado()).isEqualTo("estampado");
        assertThat(visual.escote()).isEqualTo("cuello redondo");
        assertThat(visual.colorDominante()).isEqualTo("azul");
    }

    @Test
    void visualAttrsEmptyConstantHasBlankFields() {
        assertThat(VisualAttrs.EMPTY.fit()).isEmpty();
        assertThat(VisualAttrs.EMPTY.estampado()).isEmpty();
        assertThat(VisualAttrs.EMPTY.escote()).isEmpty();
        assertThat(VisualAttrs.EMPTY.colorDominante()).isEmpty();
    }

    @Test
    void canonicalConstructorSetsVisualAttrs() {
        VisualAttrs visual = new VisualAttrs("regular", "liso", "en v", "negro");
        Product p = new Product("Sitio", "Remera básica", 5000, null,
                "http://x", "http://img", "Remera", "hombre", TALLES,
                MlScore.EMPTY, "Nike", "indumentaria", false, false,
                SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1, "", visual);

        assertThat(p.visual()).isEqualTo(visual);
        assertThat(p.visual().colorDominante()).isEqualTo("negro");
    }

    @Test
    void legacyEighteenArgConstructorDefaultsVisualToEmpty() {
        Product p = new Product("Sitio", "Remera básica", 5000, null,
                "http://x", "http://img", "Remera", "hombre", TALLES,
                MlScore.EMPTY, "Nike", "indumentaria", false, false,
                SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1, "");

        assertThat(p.visual()).isEqualTo(VisualAttrs.EMPTY);
    }

    @Test
    void legacyNineArgConstructorDefaultsVisualToEmpty() {
        Product p = new Product("Sitio", "Remera", 5000, null,
                "http://x", "http://img", "Remera", "hombre", TALLES);

        assertThat(p.visual()).isEqualTo(VisualAttrs.EMPTY);
    }
}
