package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("/api/facets deportes — counts of active rows with a sport, SQL path")
class CatalogDeportesFacetTest extends PostgresTestBase {

    private CatalogQueryRepository repo;

    @BeforeEach
    void setUp() {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        var stats = db.upsertProductos(List.of(
                producto("http://x.test/river", "Remera River Plate", "futbol"),
                producto("http://x.test/boca", "Remera Boca", "futbol"),
                producto("http://x.test/speedo", "Malla Speedo", "natacion"),
                producto("http://x.test/viejo", "Remera Vieja", "futbol"),
                producto("http://x.test/lisa", "Remera Lisa", "")));
        assertThat(stats.nuevos()).isEqualTo(5);
        new JdbcTemplate(dataSource()).update(
                "UPDATE productos SET activo = false WHERE url = ?", "http://x.test/viejo");
    }

    @Test
    @DisplayName("counts each sport over active rows and leaves out blank and inactive ones")
    void countsActiveNonBlank() {
        assertThat(repo.facetas().deportes())
                .containsExactly(
                        java.util.Map.entry("futbol", 2L),
                        java.util.Map.entry("natacion", 1L));
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
