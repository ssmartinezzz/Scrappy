package ar.scraper.web.cache;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.web.dto.MarcasPicksDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarcasPicksSubCategoriaTest {

    private static Product perfume(String nombre, String sub) {
        return Product.builder()
                .sitio("TestSitio").nombre(nombre).precio(10000)
                .url("https://t/" + nombre.replace(' ', '-')).imagenUrl("img")
                .categoria("Perfume").genero("mujer").talles(List.of()).subCategoria(sub)
                .ml(Product.MlScore.EMPTY).marca("Marca").rubro("perfumeria")
                .gymrat(false).build();
    }

    private static List<MarcasPicksDtos.Pick> picks(Product... ps) {
        AggregatedResult cat = new AggregatedResult(List.of(ps), Map.of(), Map.of(), null, 0, 0);
        return MarcasPicksView.mejores(cat, "perfumeria").stream().flatMap(c -> c.getPicks().stream()).toList();
    }

    @Test
    void aPickCarriesItsSubCategoria() {
        var out = picks(perfume("Perfume A", "Eau de Parfum"));
        assertThat(out).isNotEmpty().allSatisfy(p -> assertThat(p.getSubCategoria()).isEqualTo("Eau de Parfum"));
    }

    @Test
    void aBlankSubCategoriaIsOmittedFromTheJson() throws Exception {
        var out = picks(perfume("Perfume B", ""));
        assertThat(out).isNotEmpty().allSatisfy(p -> assertThat(p.getSubCategoria()).isNull());
        assertThat(new ObjectMapper().writeValueAsString(out.get(0))).doesNotContain("subCategoria");
    }
}
