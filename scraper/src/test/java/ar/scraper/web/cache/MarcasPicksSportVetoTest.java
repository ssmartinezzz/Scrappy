package ar.scraper.web.cache;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarcasPicksSportVetoTest {

    private static Product p(String nombre, String deporte) {
        return Product.builder()
                .sitio("TestSitio").nombre(nombre).precio(10000)
                .url("https://t/" + nombre.replace(' ', '-')).imagenUrl("img")
                .categoria("Remera").genero("hombre").talles(List.of())
                .ml(Product.MlScore.EMPTY).marca("Marca").rubro("indumentaria")
                .gymrat(false).deporte(deporte).build();
    }

    @Test
    void mejoresDropsSportAndKeepsPlain() {
        Product river = p("Remera River Plate", "futbol");
        Product plain = p("Remera Lisa", "");
        AggregatedResult cat = new AggregatedResult(List.of(river, plain), Map.of(), Map.of(), null, 0, 0);
        var urls = MarcasPicksView.mejores(cat, "").stream()
                .flatMap(c -> c.getPicks().stream()).map(MarcasPicksDtos.Pick::getUrl).toList();
        assertThat(urls).containsExactly(plain.url());
    }
}
