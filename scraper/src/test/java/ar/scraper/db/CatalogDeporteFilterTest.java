package ar.scraper.db;

import ar.scraper.catalog.CatalogFilter;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("catalog deporte filter — exact code, bound as a parameter")
class CatalogDeporteFilterTest extends PostgresTestBase {

    private CatalogQueryRepository repo;

    @BeforeEach
    void setUp() {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        var stats = db.upsertProductos(List.of(
                producto("http://x.test/river", "Remera River Plate", "futbol"),
                producto("http://x.test/speedo", "Malla Speedo", "natacion"),
                producto("http://x.test/lisa", "Remera Lisa", "")));
        assertThat(stats.nuevos()).isEqualTo(3);
    }

    @Test
    @DisplayName("a code keeps only that sport's rows")
    void filtersByExactCode() {
        var page = repo.buscar(CatalogFilter.todo().conDeporte("futbol"), "precio", 1, 50);

        assertThat(page.productos()).extracting(Product::url).containsExactly("http://x.test/river");
        assertThat(page.productos().get(0).deporte()).isEqualTo("futbol");
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    @DisplayName("null or blank applies no filter, and the catalog still lists sport rows by default")
    void blankIsNoFilter() {
        assertThat(repo.buscar(CatalogFilter.todo(), "precio", 1, 50).total()).isEqualTo(3);
        assertThat(repo.buscar(CatalogFilter.todo().conDeporte(null), "precio", 1, 50).total()).isEqualTo(3);
        assertThat(repo.buscar(CatalogFilter.todo().conDeporte("  "), "precio", 1, 50).total()).isEqualTo(3);
    }

    @Test
    @DisplayName("the value is a bind parameter, never SQL text")
    void valueIsBound() {
        var page = repo.buscar(CatalogFilter.todo().conDeporte("futbol' OR '1'='1"), "precio", 1, 50);

        assertThat(page.total()).isZero();
    }

    private Product producto(String url, String nombre, String deporte) {
        return Product.builder().sitio("Test").nombre(nombre).precio(10000.0).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Remera").genero("unisex")
                .talles(List.of("M")).ml(new Product.MlScore(70, List.of(), false, "estable", 50, 0.0, "standard"))
                .rubro("indumentaria").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).deporte(deporte).build();
    }
}
