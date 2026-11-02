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

@DisplayName("catalog excluirCategoria — drops the listed categories and keeps rows with none")
class CatalogExcluirCategoriaTest extends PostgresTestBase {

    private CatalogQueryRepository repo;

    @BeforeEach
    void setUp() {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        var stats = db.upsertProductos(List.of(
                producto("http://x.test/remera", "Remera Lisa", "Remera"),
                producto("http://x.test/otros", "Cosa Rara", "Otros"),
                producto("http://x.test/alimento", "Alfajor", "Alimentos"),
                producto("http://x.test/sin-categoria", "Sin Categoria", null)));
        assertThat(stats.nuevos()).isEqualTo(4);
    }

    @Test
    @DisplayName("excluded categories vanish from the page and the total, rows with no categoria stay")
    void excludesListedCategoriesAndKeepsNull() {
        CatalogFilter filtro = CatalogFilter.todo().conExcluirCategorias(List.of("Otros", "Alimentos"));

        CatalogPage page = repo.buscar(filtro, "precio", 1, 50);

        assertThat(page.productos()).extracting(Product::url)
                .containsExactlyInAnyOrder("http://x.test/remera", "http://x.test/sin-categoria");
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("the match is case-insensitive and a null or empty list excludes nothing")
    void caseInsensitiveAndEmptyIsNoop() {
        assertThat(repo.buscar(CatalogFilter.todo().conExcluirCategorias(List.of("otros")), "precio", 1, 50)
                .total()).isEqualTo(3);
        assertThat(repo.buscar(CatalogFilter.todo().conExcluirCategorias(List.of()), "precio", 1, 50)
                .total()).isEqualTo(4);
        assertThat(repo.buscar(CatalogFilter.todo(), "precio", 1, 50).total()).isEqualTo(4);
    }

    private Product producto(String url, String nombre, String categoria) {
        return Product.builder().sitio("Test").nombre(nombre).precio(10000.0).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria(categoria).genero("unisex")
                .talles(List.of("M")).ml(new Product.MlScore(70, List.of(), false, "estable", 50, 0.0, "standard"))
                .rubro("indumentaria").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).build();
    }
}
