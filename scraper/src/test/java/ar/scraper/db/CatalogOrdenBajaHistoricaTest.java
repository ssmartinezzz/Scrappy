package ar.scraper.db;

import ar.scraper.catalog.CatalogFilter;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("/api/data — orden=baja_historica, the biggest drop from the product's own historical max first")
class CatalogOrdenBajaHistoricaTest extends PostgresTestBase {

    private CatalogQueryRepository repo;

    @BeforeEach
    void setUp() {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        var stats = db.upsertProductos(List.of(
                producto("https://s.com/a-cayo-poco", 900),
                producto("https://s.com/b-cayo-mucho", 100),
                producto("https://s.com/c-sin-historial", 50),
                producto("https://s.com/d-plano", 500)));
        assertThat(stats.nuevos()).isEqualTo(4);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.update("DELETE FROM precio_historico");
        historia(jdbc, "https://s.com/a-cayo-poco", 1000, 900);      // -10%
        historia(jdbc, "https://s.com/b-cayo-mucho", 1000, 100);     // -90%, cheaper in pesos too
        historia(jdbc, "https://s.com/d-plano", 500, 500);           // 0%
    }

    @Test
    @DisplayName("ordered by relative drop, flat history after real drops, no-history last, total unchanged")
    void biggestRelativeDropFirstAndNoHistoryLast() {
        var page = repo.buscar(CatalogFilter.todo(), "baja_historica", 1, 50);

        assertThat(page.productos()).extracting(Product::url).containsExactly(
                "https://s.com/b-cayo-mucho",
                "https://s.com/a-cayo-poco",
                "https://s.com/d-plano",
                "https://s.com/c-sin-historial");
        assertThat(page.total()).isEqualTo(4);
    }

    private void historia(JdbcTemplate jdbc, String url, double max, double actual) {
        jdbc.update("INSERT INTO precio_historico (url, precio, fecha) VALUES (?, ?, DATE '2026-10-01')", url, max);
        jdbc.update("INSERT INTO precio_historico (url, precio, fecha) VALUES (?, ?, DATE '2026-10-20')", url, actual);
    }

    private Product producto(String url, double precio) {
        return Product.builder().sitio("Sitio").nombre("Producto " + url).precio(precio).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Remera").genero("unisex")
                .talles(List.of("M")).ml(new Product.MlScore(50, List.of(), false, "estable", 50, 0.0, "standard"))
                .rubro("indumentaria").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).build();
    }
}
