package ar.scraper.web.cache;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.catalog.ProductKey;
import ar.scraper.model.Product;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A pick carries the product's short handle, so the detail panel can hydrate it and link to /historial/{key}. */
class MarcasPicksKeyTest {

    private static Product perfume(String url) {
        return Product.builder()
                .sitio("TestSitio").nombre("Perfume").precio(10000).url(url).imagenUrl("img")
                .categoria("Perfume").genero("mujer").talles(List.of())
                .ml(Product.MlScore.EMPTY).marca("Marca").rubro("perfumeria")
                .gymrat(false).build();
    }

    @Test
    void aPickCarriesTheProductKeyOfItsUrl() {
        String url = "https://t/perfume-a";
        AggregatedResult cat = new AggregatedResult(List.of(perfume(url)), Map.of(), Map.of(), null, 0, 0);

        List<MarcasPicksDtos.Pick> out = MarcasPicksView.mejores(cat, "perfumeria").stream()
                .flatMap(c -> c.getPicks().stream()).toList();

        assertThat(out).isNotEmpty().allSatisfy(p -> assertThat(p.getKey()).isEqualTo(ProductKey.of(url)));
    }
}
