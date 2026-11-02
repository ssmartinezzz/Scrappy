package ar.scraper.db;

import ar.scraper.catalog.CatalogFilter;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("/api/data — orden=mercado_asc, the cheapest against its market first")
class CatalogOrdenMercadoTest extends PostgresTestBase {

    private CatalogQueryRepository repo;

    @BeforeEach
    void setUp() {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        // Prices run opposite to the score, so a fall-through to precio_asc cannot pass by luck.
        var stats = db.upsertProductos(List.of(
                producto("https://s.com/b-caro-en-su-mercado", 100, 90),
                producto("https://s.com/a-barato-en-su-mercado", 9000, 5),
                producto("https://s.com/c-medio", 500, 40),
                producto("https://s.com/d-medio-empate", 700, 40)));
        assertThat(stats.nuevos()).isEqualTo(4);
    }

    @Test
    @DisplayName("lowest composite score first, ties broken by url, and the total is untouched")
    void lowestScoreFirstWithUrlTiebreak() {
        var page = repo.buscar(CatalogFilter.todo(), "mercado_asc", 1, 50);

        assertThat(page.productos()).extracting(Product::url).containsExactly(
                "https://s.com/a-barato-en-su-mercado",
                "https://s.com/c-medio",
                "https://s.com/d-medio-empate",
                "https://s.com/b-caro-en-su-mercado");
        assertThat(page.total()).isEqualTo(4);
    }

    private Product producto(String url, double precio, int score) {
        Product.MlScore ml = new Product.MlScore(score, List.of(), false, "estable", score, 0.0, "standard");
        return Product.builder().sitio("Sitio").nombre("Producto " + url).precio(precio).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Remera").genero("unisex")
                .talles(List.of("M")).ml(ml).rubro("indumentaria").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).build();
    }
}
