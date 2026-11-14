package ar.scraper.web.cache;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A toy store files every product as infantil; the kids veto must not empty the juguetes picks. */
class MarcasPicksJuguetesInfantilTest {

    private static Product p(String nombre, String categoria, String rubro) {
        return Product.builder()
                .sitio("Elmundodeljuguete").nombre(nombre).precio(10000)
                .url("https://t/" + nombre.replace(' ', '-')).imagenUrl("img")
                .categoria(categoria).genero("infantil").talles(List.of())
                .ml(Product.MlScore.EMPTY).marca("Marca").rubro(rubro)
                .gymrat(false).deporte("").build();
    }

    private static List<String> urls(AggregatedResult cat, String rubro) {
        return MarcasPicksView.mejores(cat, rubro).stream()
                .flatMap(c -> c.getPicks().stream()).map(MarcasPicksDtos.Pick::getUrl).toList();
    }

    @Test
    void infantilToyStaysInJuguetesPicks() {
        Product lego = p("Lego City Camion", "LEGO", "juguetes");
        AggregatedResult cat = new AggregatedResult(List.of(lego), Map.of(), Map.of(), null, 0, 0);
        assertThat(urls(cat, "juguetes")).containsExactly(lego.url());
        assertThat(urls(cat, "")).containsExactly(lego.url());
    }

    @Test
    void infantilClothingIsStillVetoed() {
        Product remera = p("Remera Nene", "Remera", "indumentaria");
        AggregatedResult cat = new AggregatedResult(List.of(remera), Map.of(), Map.of(), null, 0, 0);
        assertThat(urls(cat, "")).isEmpty();
    }
}
