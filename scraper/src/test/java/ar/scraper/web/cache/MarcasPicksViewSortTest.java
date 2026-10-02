package ar.scraper.web.cache;

import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.model.Product;
import ar.scraper.web.dto.MarcasPicksDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MarcasPicksView.marcas - sort orders")
class MarcasPicksViewSortTest {

    private static void add(List<Product> out, String marca, double precio, int n) {
        for (int i = 0; i < n; i++) {
            out.add(Product.builder().sitio("S").nombre(marca + i).precio(precio)
                    .url("https://t/" + marca + i).marca(marca).categoria("Remera").build());
        }
    }

    private static List<String> orden(String sort) {
        List<Product> productos = new ArrayList<>();
        add(productos, "Cheap", 100, 2);
        add(productos, "Mid", 200, 4);
        add(productos, "Dear", 300, 3);
        var r = new AggregatedResult(productos, Map.of(), Map.of(), null, 0, 0);
        return MarcasPicksView.marcas(r, "", "", sort).stream().map(MarcasPicksDtos.Marca::getMarca).toList();
    }

    @Test
    void defaultSortsByProductCountDescending() {
        assertThat(orden("")).containsExactly("Mid", "Dear", "Cheap");
    }

    @Test
    void precioAscSortsByAveragePriceAscending() {
        assertThat(orden("precio_asc")).containsExactly("Cheap", "Mid", "Dear");
    }

    @Test
    void precioDescSortsByAveragePriceDescending() {
        assertThat(orden("precio_desc")).containsExactly("Dear", "Mid", "Cheap");
    }
}
