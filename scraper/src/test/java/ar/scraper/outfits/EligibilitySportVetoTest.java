package ar.scraper.outfits;

import ar.scraper.model.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A sport-specific product is kept out of every builder/feed entry point; a plain one stays. */
@DisplayName("Sport veto at the builder and feed choke points")
class EligibilitySportVetoTest {

    private static Product p(String nombre, String deporte) {
        return Product.builder()
                .sitio("TestSitio").nombre(nombre).precio(10000)
                .url("https://t/" + nombre.replace(' ', '-')).imagenUrl("img")
                .categoria("Remera").genero("hombre").talles(List.of())
                .ml(Product.MlScore.EMPTY).marca("Marca").rubro("indumentaria")
                .gymrat(false).deporte(deporte).build();
    }

    private final Product river = p("Remera River Plate", "futbol");
    private final Product plain = p("Remera Lisa", "");

    @Test
    void generoElegibleRejectsSportAndKeepsPlain() {
        assertThat(OutfitRules.generoElegible(river, "hombre")).isFalse();
        assertThat(OutfitRules.generoElegible(river, "")).isFalse();
        assertThat(OutfitRules.generoElegible(plain, "hombre")).isTrue();
    }

    @Test
    void recomendadosRankDropsSport() {
        List<Product> ranked = new RecommendationService()
                .rank(List.of(river, plain), OutfitService.FeedbackModel.empty());
        assertThat(ranked).containsExactly(plain);
    }
}
