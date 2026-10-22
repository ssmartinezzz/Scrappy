package ar.scraper.db;

import ar.scraper.catalog.CatalogFilter;
import ar.scraper.catalog.CatalogPage;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("catalog q — matches the brand as well as the name")
class CatalogQueryMarcaTest extends PostgresTestBase {

    private CatalogQueryRepository repo;

    @BeforeEach
    void setUp() {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        var stats = db.upsertProductos(List.of(
                producto("http://monky.test/a", "Buzo Oversize", "Monkyforce", "Monkyforce"),
                producto("http://monky.test/b", "Remera Lisa", "Monkyforce", "Monkyforce"),
                producto("http://midway.test/c", "Remera Básica", "Midway", null)));
        assertThat(stats.nuevos()).isEqualTo(3);
    }

    @Test
    @DisplayName("a query that is only in the brand finds the products, and the total agrees")
    void qMatchesMarca() {
        CatalogPage page = repo.buscar(CatalogFilter.todo().conQ("monky"), "precio", 1, 50);

        assertThat(page.productos()).extracting(Product::url)
                .containsExactlyInAnyOrder("http://monky.test/a", "http://monky.test/b");
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("a name match still works and a % is still a literal")
    void qStillMatchesNombre() {
        assertThat(repo.buscar(CatalogFilter.todo().conQ("básica"), "precio", 1, 50).total()).isEqualTo(1);
        assertThat(repo.buscar(CatalogFilter.todo().conQ("%"), "precio", 1, 50).total()).isZero();
    }

    private Product producto(String url, String nombre, String sitio, String marca) {
        return Product.builder().sitio(sitio).nombre(nombre).precio(10000.0).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Remera").genero("unisex")
                .talles(List.of("M")).ml(new Product.MlScore(70, List.of(), false, "estable", 50, 0.0, "standard"))
                .marca(marca).rubro("indumentaria").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).build();
    }
}
