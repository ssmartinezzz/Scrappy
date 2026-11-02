package ar.scraper.db;

import ar.scraper.catalog.CatalogFilter;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProductPort.actualizarMl — a re-applied ML run reaches the stored rows")
class ProductRepositoryActualizarMlTest extends PostgresTestBase {

    private DatabaseService db;
    private CatalogQueryRepository repo;

    @BeforeEach
    void setUp() {
        db = TestDatabaseServices.create(dataSource());
        repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        var stats = db.upsertProductos(List.of(
                producto("https://s.com/a", 50, List.of("all_time_low"), false, "estable", "standard", 0.0),
                producto("https://s.com/b", 50, List.of("trending"), false, "estable", "standard", 0.0)));
        assertThat(stats.nuevos()).isEqualTo(2);
    }

    @Test
    @DisplayName("scores, flags and badges are replaced, in order, for the stored rows only")
    void replacesMlFieldsAndBadges() {
        int n = db.productos().actualizarMl(List.of(
                producto("https://s.com/a", 12, List.of("below_market", "verified_deal"), true, "bajando", "budget", -1.5),
                producto("https://s.com/no-existe", 12, List.of("trending"), true, "bajando", "budget", -1.5)));

        assertThat(n).isEqualTo(1);
        Product a = db.productos().obtenerProducto("https://s.com/a").orElseThrow();
        assertThat(a.ml().scoreP()).isEqualTo(12);
        assertThat(a.ml().ofertaReal()).isTrue();
        assertThat(a.ml().tendencia()).isEqualTo("bajando");
        assertThat(a.ml().segment()).isEqualTo("budget");
        assertThat(a.ml().zScore()).isEqualTo(-1.5);
        assertThat(a.ml().badges()).containsExactly("below_market", "verified_deal");
        assertThat(repo.buscar(CatalogFilter.todo().conBadge("all_time_low"), "precio", 1, 50).total()).isZero();
        assertThat(repo.buscar(CatalogFilter.todo().conBadge("below_market"), "precio", 1, 50).total()).isEqualTo(1);
        // untouched neighbour, and no product was inserted
        assertThat(db.productos().obtenerProducto("https://s.com/b").orElseThrow().ml().badges())
                .containsExactly("trending");
        assertThat(db.productos().obtenerProducto("https://s.com/no-existe")).isEmpty();
        assertThat(repo.buscar(CatalogFilter.todo(), "precio", 1, 50).total()).isEqualTo(2);
    }

    @Test
    @DisplayName("an empty badge set clears the stored badges")
    void emptyBadgesClear() {
        db.productos().actualizarMl(List.of(
                producto("https://s.com/a", 50, List.of(), false, "estable", "standard", 0.0)));

        assertThat(db.productos().obtenerProducto("https://s.com/a").orElseThrow().ml().badges()).isEmpty();
    }

    private Product producto(String url, int score, List<String> badges, boolean oferta,
                             String tendencia, String segment, double z) {
        Product.MlScore ml = new Product.MlScore(score, badges, oferta, tendencia, score, z, segment);
        return Product.builder().sitio("Sitio").nombre("Producto " + url).precio(1000.0).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Remera").genero("unisex")
                .talles(List.of("M")).ml(ml).rubro("indumentaria").gymrat(false).marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY).finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1).build();
    }
}
