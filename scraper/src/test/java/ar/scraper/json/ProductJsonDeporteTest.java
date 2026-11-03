package ar.scraper.json;

import ar.scraper.model.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProductJson — deporte is always on the wire")
class ProductJsonDeporteTest {

    private static ObjectNode escribir(String deporte) {
        Product p = Product.builder().sitio("T").nombre("Remera").precio(100).url("http://t/x").imagenUrl("img")
                .categoria("Remera").genero("unisex").talles(List.of()).ml(Product.MlScore.EMPTY)
                .marca("M").rubro("indumentaria").gymrat(false).deporte(deporte).build();
        ObjectNode n = new ObjectMapper().createObjectNode();
        ProductJson.escribir(n, p);
        return n;
    }

    @Test
    void emitsTheSportCode() {
        assertThat(escribir("futbol").get("deporte").asText()).isEqualTo("futbol");
    }

    @Test
    void emitsAnEmptyStringWhenThereIsNone() {
        assertThat(escribir("").get("deporte").asText()).isEmpty();
        assertThat(escribir(null).get("deporte").asText()).isEmpty();
    }
}
