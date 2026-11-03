package ar.scraper.outfits;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EligibilityPolicy — one veto for builders and feeds")
class EligibilityPolicyTest {

    private static Product p(String nombre, String genero, String deporte) {
        return Product.builder()
                .sitio("TestSitio").nombre(nombre).precio(10000)
                .url("https://t/" + nombre.replace(' ', '-')).imagenUrl("img")
                .categoria("Remera").genero(genero).talles(List.of())
                .ml(Product.MlScore.EMPTY).marca("Marca").rubro("indumentaria")
                .gymrat(false).deporte(deporte).build();
    }

    @Test
    void plainGarmentIsEligible() {
        assertThat(EligibilityPolicy.elegible(p("Remera Lisa", "hombre", ""))).isTrue();
    }

    @Test
    void blankOrNullGeneroAndDeporteAreEligible() {
        assertThat(EligibilityPolicy.elegible(p("Remera", null, null))).isTrue();
        assertThat(EligibilityPolicy.elegible(p("Remera", "", "  "))).isTrue();
    }

    @Test
    void infantilIsVetoedWhateverTheCase() {
        assertThat(EligibilityPolicy.elegible(p("Remera Kids", "infantil", ""))).isFalse();
        assertThat(EligibilityPolicy.elegible(p("Remera Kids", " Infantil ", ""))).isFalse();
    }

    @Test
    void anySportIsVetoed() {
        assertThat(EligibilityPolicy.elegible(p("Remera River Plate", "hombre", "futbol"))).isFalse();
        assertThat(EligibilityPolicy.elegible(p("Malla Speedo", "mujer", "natacion"))).isFalse();
    }

    @Test
    void nullProductIsNotEligible() {
        assertThat(EligibilityPolicy.elegible(null)).isFalse();
    }
}
