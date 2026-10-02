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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Domain Model")
@Feature("Product")
@DisplayName("Product — builder")
class ProductBuilderTest {

    private static final List<String> TALLES = List.of("M");

    @Test
    void builderDefaultsMatchTheLegacyTail() {
        Product built = Product.builder()
                .sitio("Sitio").nombre("Remera").precio(100).precioOriginal(null)
                .url("http://x").imagenUrl("http://img").categoria("Remera").genero("hombre")
                .talles(TALLES)
                .build();

        Product explicit = new Product("Sitio", "Remera", 100, null,
                "http://x", "http://img", "Remera", "hombre", TALLES,
                MlScore.EMPTY, "", "indumentaria", false, false,
                SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 1, "", VisualAttrs.EMPTY);

        assertThat(built).isEqualTo(explicit);
    }

    @Test
    void toBuilderRoundTripsAFullyPopulatedProduct() {
        Product full = new Product("Sitio", "Pack x3", 15000, 20000.0,
                "http://x", "http://img", "Remera", "mujer", TALLES,
                new MlScore(80, List.of("oferta"), true, "baja", 90, 1.5, "premium"),
                "Nike", "tecnologia", true, true,
                SenalCompra.EMPTY, SenalFinanciacion.EMPTY, 3, "Basica", VisualAttrs.EMPTY);

        assertThat(full.toBuilder().build()).isEqualTo(full);
        assertThat(full.toBuilder().precio(1).build().precio()).isEqualTo(1);
    }

    @Test
    void rejectsABlankSitio() {
        assertThatThrownBy(() -> Product.builder().sitio("  ").nombre("Remera").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Product.builder().sitio("").nombre("Remera").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankNombre() {
        assertThatThrownBy(() -> Product.builder().sitio("Sitio").nombre(" \t").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Product.builder().sitio("Sitio").nombre("").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullSitioOrNombre() {
        assertThatThrownBy(() -> Product.builder().nombre("Remera").build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Product.builder().sitio("Sitio").build())
                .isInstanceOf(NullPointerException.class);
    }
}
