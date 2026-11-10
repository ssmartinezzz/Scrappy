package ar.scraper.db;

import ar.scraper.catalog.CatalogCursor;
import ar.scraper.catalog.CatalogFilter;
import ar.scraper.catalog.CatalogKeysetPage;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("catalog keyset pagination — (precio, url) cursor, no OFFSET")
class CatalogKeysetTest extends PostgresTestBase {

    private CatalogQueryRepository repo;
    private final List<Product> sembrados = new ArrayList<>();

    @BeforeEach
    void setUp() {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        // 130 products of one brand with only 7 distinct prices, so every page boundary lands inside a tie group,
        // plus 20 of another brand that must never leak in.
        for (int i = 0; i < 130; i++) sembrados.add(producto("http://x.test/n" + String.format("%03d", i), "Nike", 1000 + (i % 7) * 500));
        for (int i = 0; i < 20; i++) sembrados.add(producto("http://x.test/a" + String.format("%03d", i), "Adidas", 1000 + (i % 3) * 500));
        db.upsertProductos(List.copyOf(sembrados));
    }

    private List<String> recorrer(String orden, int size) {
        List<String> vistos = new ArrayList<>();
        CatalogCursor cursor = null;
        for (int guard = 0; guard < 1000; guard++) {
            CatalogKeysetPage p = repo.buscarPorCursor(CatalogFilter.todo().conMarcas(List.of("nike")), orden, cursor, size, Optional.empty());
            p.productos().forEach(x -> vistos.add(x.url()));
            if (p.siguiente() == null) return vistos;
            cursor = p.siguiente();
        }
        throw new AssertionError("the cursor never ended");
    }

    @Test
    @DisplayName("precio_asc walks every row of the filter exactly once, price then url")
    void ascendente() {
        List<String> esperado = sembrados.stream().filter(x -> "Nike".equals(x.marca()))
                .sorted(Comparator.comparingDouble(Product::precio).thenComparing(Product::url)).map(Product::url).toList();

        assertThat(recorrer("precio_asc", 16)).containsExactlyElementsOf(esperado);
    }

    @Test
    @DisplayName("precio_desc walks every row exactly once, price descending")
    void descendente() {
        List<String> vistos = recorrer("precio_desc", 16);

        assertThat(vistos).hasSize(130).doesNotHaveDuplicates();
        List<Double> precios = vistos.stream().map(u -> sembrados.stream().filter(x -> x.url().equals(u)).findFirst().orElseThrow().precio()).toList();
        assertThat(precios).isSortedAccordingTo(Comparator.reverseOrder());
    }

    @Test
    @DisplayName("the last page has no cursor, an exact multiple of the size included")
    void ultimaPaginaSinCursor() {
        CatalogCursor cursor = null;
        int paginas = 0;
        do {
            var p = repo.buscarPorCursor(CatalogFilter.todo().conMarcas(List.of("nike")), "precio_asc", cursor, 65, Optional.empty());
            cursor = p.siguiente();
            paginas++;
        } while (cursor != null);
        assertThat(paginas).isEqualTo(2);
    }

    @Test
    @DisplayName("only precio_asc and precio_desc support a cursor")
    void ordenNoSoportado() {
        assertThatThrownBy(() -> repo.buscarPorCursor(CatalogFilter.todo(), "nombre_asc", null, 10, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the cursor round-trips and rejects garbage")
    void cursorRoundTrip() {
        var c = new CatalogCursor(1999.9, "http://x.test/a|b");
        assertThat(CatalogCursor.decodificar(c.codificar())).isEqualTo(c);
        assertThatThrownBy(() -> CatalogCursor.decodificar("###")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CatalogCursor.decodificar("bm8tcGlwZQ")).isInstanceOf(IllegalArgumentException.class);
    }

    private static Product producto(String url, String marca, double precio) {
        return Product.builder().sitio("Test").nombre("Producto " + url).precio(precio).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Remera").genero("unisex").marca(marca)
                .talles(List.of("M")).ml(new Product.MlScore(70, List.of(), false, "estable", 50, 0.0, "standard"))
                .rubro("indumentaria").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).build();
    }
}
