package ar.scraper.db.migration;

import ar.scraper.db.DatabaseService;
import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * normalize-db-schema-fks-1nf, slice B (V7).
 *
 * <p>Structural contract of the two multi-value child tables that replace
 * {@code productos.talles} (JSON-in-TEXT) and {@code productos.ml_badge}
 * (CSV): both are keyed {@code (url, posicion)} so order is modelled
 * explicitly — {@code ml_badge} was documented "comma-delimited,
 * principal-first" and {@code badges().get(0)} is the principal badge, so a
 * set with no ordinal would silently lose that (design D3).</p>
 *
 * <p>The two legacy columns MUST be gone: leaving them in place would create
 * two sources of truth for the same fact, which is the 1NF violation this
 * slice exists to remove.</p>
 */
@Epic("Persistence")
@Feature("Multi-value normalization")
@Story("producto_talle / producto_badge structure — V7")
@DisplayName("V7 migration — producto_talle / producto_badge structure")
class ProductoChildTablesSchemaTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    @Test
    @DisplayName("The legacy multi-value columns are dropped from productos")
    void legacyMultiValueColumnsAreGone() throws Exception {
        assertThat(productosColumns())
                .doesNotContain("talles")
                .doesNotContain("ml_badge");
    }

    @Test
    @DisplayName("Both child tables are keyed (url, posicion)")
    void bothChildTablesArePrimaryKeyedByUrlAndPosicion() throws Exception {
        assertThat(primaryKeyColumns("producto_talle")).containsExactly("url", "posicion");
        assertThat(primaryKeyColumns("producto_badge")).containsExactly("url", "posicion");
    }

    @Test
    @DisplayName("posicion is SMALLINT and the value column is NOT NULL")
    void childTableColumnsHaveTheirDeclaredTypes() throws Exception {
        assertThat(dataType("producto_talle", "posicion")).isEqualTo("smallint");
        assertThat(dataType("producto_badge", "posicion")).isEqualTo("smallint");
        assertThat(isNullable("producto_talle", "talle")).isFalse();
        assertThat(isNullable("producto_badge", "badge")).isFalse();
    }

    @Test
    @DisplayName("Deleting a product cascades both child tables")
    void deletingProductCascadesChildRows() throws Exception {
        String url = "https://site.com/child-cascade";
        db.upsertProductos(List.of(producto(url, List.of("S", "M"), List.of("verified_deal", "trending"))));

        assertThat(contar("producto_talle", url)).isEqualTo(2);
        assertThat(contar("producto_badge", url)).isEqualTo(2);

        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM productos WHERE url=?")) {
            ps.setString(1, url);
            ps.executeUpdate();
        }

        assertThat(contar("producto_talle", url)).isZero();
        assertThat(contar("producto_badge", url)).isZero();
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private ar.scraper.model.Product producto(String url, List<String> talles, List<String> badges) {
        ar.scraper.model.Product.MlScore ml = new ar.scraper.model.Product.MlScore(
                80, badges, true, "estable", 20, 0.5, "standard");
        return new ar.scraper.model.Product("Sitio", "Producto", 1000.0, null, url,
                "http://img.example/x.jpg", "Remera", "unisex", talles, ml, "Nike",
                "indumentaria", false, false, ar.scraper.model.Product.SenalCompra.EMPTY,
                ar.scraper.model.Product.SenalFinanciacion.EMPTY, 1);
    }

    private int contar(String tabla, String url) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + tabla + " WHERE url=?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private List<String> productosColumns() throws Exception {
        return queryStrings(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='productos' ORDER BY ordinal_position");
    }

    private List<String> primaryKeyColumns(String table) throws Exception {
        return queryStrings(
                "SELECT a.attname FROM pg_index i "
                        + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                        + "WHERE i.indrelid = '" + table + "'::regclass AND i.indisprimary "
                        + "ORDER BY array_position(i.indkey, a.attnum)");
    }

    private String dataType(String table, String column) throws Exception {
        List<String> rows = queryStrings(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='" + table + "' "
                        + "AND column_name='" + column + "'");
        assertThat(rows).as("column %s.%s exists", table, column).hasSize(1);
        return rows.get(0);
    }

    private boolean isNullable(String table, String column) throws Exception {
        List<String> rows = queryStrings(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='" + table + "' "
                        + "AND column_name='" + column + "'");
        assertThat(rows).as("column %s.%s exists", table, column).hasSize(1);
        return "YES".equals(rows.get(0));
    }

    private List<String> queryStrings(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }
}
