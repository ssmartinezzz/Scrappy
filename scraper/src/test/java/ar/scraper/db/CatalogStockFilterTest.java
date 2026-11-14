package ar.scraper.db;

import ar.scraper.catalog.CatalogFilter;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("catalog stock filter — confirmed sold-out rows only, bound cutoff, keyset-safe")
class CatalogStockFilterTest extends PostgresTestBase {

    private CatalogQueryRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        var stats = db.upsertProductos(List.of(
                producto("http://x.test/en", 100, true),
                producto("http://x.test/sin", 200, false),
                producto("http://x.test/desc", 300, null),
                producto("http://x.test/sin-viejo", 400, false)));
        assertThat(stats.nuevos()).isEqualTo(4);
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE productos SET stock_chequeado_at = "
                     + "stock_chequeado_at - interval '30 days' WHERE url = 'http://x.test/sin-viejo'")) {
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    private static Instant corte() {
        return Instant.now().minus(Duration.ofDays(7));
    }

    @Test
    @DisplayName("no cutoff is no filter")
    void noFilter() {
        assertThat(repo.buscar(CatalogFilter.todo(), "precio_asc", 1, 50).total()).isEqualTo(4);
    }

    @Test
    @DisplayName("with a cutoff, a fresh false leaves; true, null and a stale false stay")
    void dropsOnlyFreshFalse() {
        var page = repo.buscar(CatalogFilter.todo().conExcluirSinStock(corte()), "precio_asc", 1, 50);

        assertThat(page.productos()).extracting(Product::url)
                .containsExactly("http://x.test/en", "http://x.test/desc", "http://x.test/sin-viejo");
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    @DisplayName("keyset reads apply the same predicate")
    void keysetAppliesPredicate() {
        var pagina = repo.buscarPorCursor(CatalogFilter.todo().conExcluirSinStock(corte()), "precio_asc", null, 50,
                Optional.empty());

        assertThat(pagina.productos()).extracting(Product::url)
                .containsExactly("http://x.test/en", "http://x.test/desc", "http://x.test/sin-viejo");
    }

    @Test
    @DisplayName("a cutoff in the far past makes the stale false count as sold out")
    void cutoffControlsStaleness() {
        var page = repo.buscar(CatalogFilter.todo().conExcluirSinStock(Instant.now().minus(Duration.ofDays(365))),
                "precio_asc", 1, 50);

        assertThat(page.productos()).extracting(Product::url)
                .containsExactly("http://x.test/en", "http://x.test/desc");
    }

    private static Product producto(String url, double precio, Boolean enStock) {
        return Product.builder().sitio("Test").nombre("Producto " + url).precio(precio).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Remera").genero("unisex")
                .talles(List.of("M")).rubro("indumentaria").enStock(enStock).build();
    }
}
