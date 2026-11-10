package ar.scraper.web.cache;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A rubro with many categories must not lose the small ones (Bombacha ranked 52 of 54). */
class MarcasPicksTodasLasCategoriasTest {

    private static Product p(String categoria, int i) {
        return Product.builder()
                .sitio("Fuark").nombre(categoria + " " + i).precio(1000)
                .url("https://s/" + categoria + i).imagenUrl("img").categoria(categoria).genero("unisex")
                .talles(List.of("M")).ml(Product.MlScore.EMPTY).marca("Fuark").rubro("indumentaria")
                .gymrat(false).marcaPremium(false).senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY).cantidadUnidades(1).subCategoria("")
                .visual(Product.VisualAttrs.EMPTY).build();
    }

    @Test
    void keepsEveryCategoryEvenWhenThereAreMoreThanFortyOne() {
        List<Product> ps = new ArrayList<>();
        for (int c = 0; c < 54; c++) {
            for (int i = 0; i <= 54 - c; i++) ps.add(p("Cat" + c, i));
        }
        ps.add(p("Bombacha", 0));
        var r = new AggregatedResult(ps, Map.of(), Map.of(), null, 0, 0);

        var cards = MarcasPicksView.mejores(r, "indumentaria");

        assertThat(cards).hasSize(55);
        assertThat(cards).extracting(c -> c.getCategoria()).contains("Bombacha");
    }

    @Test
    void offersUpToTwentyPicksPerCategory() {
        List<Product> ps = new ArrayList<>();
        for (int i = 0; i < 30; i++) ps.add(p("Remera", i));
        var r = new AggregatedResult(ps, Map.of(), Map.of(), null, 0, 0);

        var cards = MarcasPicksView.mejores(r, "indumentaria");

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getPicks()).hasSize(20);
    }
}
