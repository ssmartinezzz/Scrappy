package ar.scraper.db;

import ar.scraper.catalog.CatalogFilter;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("catalog stock filter total — COUNT by subtraction equals the row-level predicate, and V100 indexes exist")
class CatalogStockCountTest extends PostgresTestBase {

    private static final String PREDICADO_VIEJO =
            "(en_stock IS DISTINCT FROM FALSE OR stock_chequeado_at IS NULL OR stock_chequeado_at < ?)";

    private CatalogQueryRepository repo;

    @BeforeEach
    void setUp() throws SQLException {
        DatabaseService db = TestDatabaseServices.create(dataSource());
        repo = new CatalogQueryRepository(dataSource(), db.siteRegistry());
        // Nike: en / sin fresh / sin stale / null+checked / null+never. Adidas: en / sin fresh.
        var stats = db.upsertProductos(List.of(
                producto("http://x.test/n-en", "Nike", 100, true),
                producto("http://x.test/n-sin", "Nike", 200, false),
                producto("http://x.test/n-sin-viejo", "Nike", 300, false),
                producto("http://x.test/n-nulo-chequeado", "Nike", 400, null),
                producto("http://x.test/n-nulo", "Nike", 500, null),
                producto("http://x.test/a-en", "Adidas", 600, true),
                producto("http://x.test/a-sin", "Adidas", 700, false)));
        assertThat(stats.nuevos()).isEqualTo(7);
        ejecutar("UPDATE productos SET stock_chequeado_at = stock_chequeado_at - interval '30 days' "
                + "WHERE url = 'http://x.test/n-sin-viejo'");
        ejecutar("UPDATE productos SET stock_chequeado_at = now() WHERE url = 'http://x.test/n-nulo-chequeado'");
    }

    private static Instant corte() {
        return Instant.now().minus(Duration.ofDays(7));
    }

    @Test
    @DisplayName("total with the filter equals the old predicate's row count across every stock state")
    void totalMatchesOldPredicate() throws SQLException {
        Instant corte = corte();
        int esperado = contarConPredicadoViejo("activo", corte);

        var page = repo.buscar(CatalogFilter.todo().conExcluirSinStock(corte), "precio_asc", 1, 50);

        assertThat(esperado).isEqualTo(5);
        assertThat(page.total()).isEqualTo(esperado);
        assertThat(page.productos()).hasSize(esperado);
    }

    @Test
    @DisplayName("combined with another filter, the total still equals the old predicate's count")
    void totalWithBrandFilter() throws SQLException {
        Instant corte = corte();
        int esperado = contarConPredicadoViejo("activo AND marca = 'Adidas'", corte);

        var page = repo.buscar(CatalogFilter.todo().conMarcas(List.of("Adidas")).conExcluirSinStock(corte),
                "precio_asc", 1, 50);

        assertThat(esperado).isEqualTo(1);
        assertThat(page.total()).isEqualTo(esperado);
    }

    @Test
    @DisplayName("when nothing is excluded the total is the whole filtered set")
    void zeroExcluded() throws SQLException {
        Instant corte = Instant.now().plus(Duration.ofDays(1)); // every check is older than this: nothing is fresh
        int esperado = contarConPredicadoViejo("activo AND marca = 'Nike'", corte);

        var page = repo.buscar(CatalogFilter.todo().conMarcas(List.of("Nike")).conExcluirSinStock(corte),
                "precio_asc", 1, 50);

        assertThat(esperado).isEqualTo(5);
        assertThat(page.total()).isEqualTo(5);
    }

    @Test
    @DisplayName("V100 creates the partial indexes for the excluded set and the default price order")
    void indicesV100Existen() throws SQLException {
        assertThat(definicionDeIndice("idx_productos_sin_stock"))
                .contains("stock_chequeado_at").contains("en_stock = false");
        assertThat(definicionDeIndice("idx_productos_activo_precio"))
                .contains("precio, url").contains("WHERE activo");
    }

    private int contarConPredicadoViejo(String where, Instant corte) throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM productos WHERE " + where + " AND " + PREDICADO_VIEJO)) {
            ps.setObject(1, corte.atOffset(java.time.ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private String definicionDeIndice(String nombre) throws SQLException {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT indexdef FROM pg_indexes WHERE indexname = ?")) {
            ps.setString(1, nombre);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    private void ejecutar(String sql) throws SQLException {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(st.executeUpdate(sql)).isEqualTo(1);
        }
    }

    private static Product producto(String url, String marca, double precio, Boolean enStock) {
        return Product.builder().sitio("Test").nombre("Producto " + url).precio(precio).url(url)
                .imagenUrl("http://img.example/x.jpg").categoria("Remera").genero("unisex").marca(marca)
                .talles(List.of("M")).rubro("indumentaria").enStock(enStock).build();
    }
}
