package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("V62..V73 — the documented Easy rollbacks compose newest first")
class EasyMigrationsRollbackChainTest extends PostgresTestBase {

    private static final List<String> NEWEST_FIRST = List.of(
            "V73", "V72", "V71", "V70", "V69", "V68", "V67", "V66", "V65", "V64", "V63", "V62");

    @Test
    @DisplayName("Each block removes exactly the categories its migration added")
    void eachBlockRemovesItsOwnCategories() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                Map<String, Integer> removed = new LinkedHashMap<>();
                for (String version : NEWEST_FIRST) {
                    int before = count(st, "SELECT count(*) FROM categoria");
                    st.execute(DocumentedRollback.sqlFor(version));
                    removed.put(version, before - count(st, "SELECT count(*) FROM categoria"));
                }

                assertThat(removed).containsExactly(
                        Map.entry("V73", 0), Map.entry("V72", 0), Map.entry("V71", 0), Map.entry("V70", 0),
                        Map.entry("V69", 9), Map.entry("V68", 50), Map.entry("V67", 27), Map.entry("V66", 21),
                        Map.entry("V65", 14), Map.entry("V64", 26), Map.entry("V63", 0), Map.entry("V62", 0));
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("The chain leaves the V61 schema: no tree, no default rubro, no index, narrow rubro domain, no Easy")
    void chainLeavesTheV61Schema() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                runChain(st);

                assertThat(count(st, "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_productos_rubro_precio'"))
                        .isZero();
                assertThat(count(st, "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = 'sitio_categoria'")).isZero();
                assertThat(columnExists(st, "productos", "sitio_categoria_id")).isFalse();
                assertThat(columnExists(st, "sitio", "rubro_por_defecto")).isFalse();
                assertThat(count(st, "SELECT count(*) FROM pg_proc WHERE proname IN "
                        + "('sp_upsert_sitio_categoria', 'sp_vincular_sitio_categoria')")).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key = 'easy'")).isZero();
                assertThatThrownBy(() -> st.executeUpdate("""
                        INSERT INTO productos (url, sitio, nombre, precio, rubro)
                        VALUES ('https://rollback.test/deporte', 'Sporting', 'Bicicleta', 1000, 'deporte')
                        """)).hasMessageContaining("chk_productos_rubro_domain");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("Categories that existed before V62 survive, including the ones the Easy taxonomy reuses")
    void chainKeepsPreexistingCategories() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                runChain(st);

                assertThat(count(st, "SELECT count(*) FROM categoria WHERE nombre IN "
                        + "('Iluminación', 'Piso', 'Moldura', 'Adhesivo', 'Silla', 'Escritorio', 'Pelota', 'Paleta')"))
                        .isEqualTo(8);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("A product in a rolled back category or rubro survives with both set to NULL")
    void liveProductsSurviveWithNullClassification() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                insertProduct(st, "https://rollback.test/cemento", "Sporting", "Cemento", "construccion");
                insertProduct(st, "https://rollback.test/bicicleta", "Sporting", "Bicicleta", "deporte");

                runChain(st);

                assertThat(count(st, "SELECT count(*) FROM productos WHERE url LIKE 'https://rollback.test/%'"))
                        .isEqualTo(2);
                assertThat(count(st, "SELECT count(*) FROM productos "
                        + "WHERE url LIKE 'https://rollback.test/%' AND categoria IS NOT NULL")).isZero();
                assertThat(count(st, "SELECT count(*) FROM productos "
                        + "WHERE url = 'https://rollback.test/bicicleta' AND rubro IS NULL")).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM productos "
                        + "WHERE url = 'https://rollback.test/cemento' AND rubro = 'construccion'")).isEqualTo(1);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("Once Easy has products, V62 keeps the site row instead of failing or orphaning them")
    void easyWithProductsKeepsItsSiteRow() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                insertProduct(st, "https://rollback.test/easy", "Easy", "Tornillo", "construccion");

                runChain(st);

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key = 'easy'")).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM productos WHERE url = 'https://rollback.test/easy'"))
                        .isEqualTo(1);
            } finally {
                c.rollback();
            }
        }
    }

    private static void runChain(Statement st) throws Exception {
        for (String version : NEWEST_FIRST) {
            st.execute(DocumentedRollback.sqlFor(version));
        }
    }

    private static void insertProduct(Statement st, String url, String sitio, String categoria, String rubro)
            throws Exception {
        st.executeUpdate("""
                INSERT INTO productos (url, sitio, nombre, precio, rubro, categoria)
                VALUES ('%s', '%s', 'Producto de prueba', 1000, '%s', '%s')
                """.formatted(url, sitio, rubro, categoria));
        st.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    private static boolean columnExists(Statement st, String tabla, String columna) throws Exception {
        return count(st, "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND table_name = '" + tabla + "' AND column_name = '" + columna + "'") > 0;
    }

    private static int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
