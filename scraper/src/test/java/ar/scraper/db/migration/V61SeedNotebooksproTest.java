package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V61 — NotebooksPro: WooCommerce tecnologia seed and a contained rollback")
class V61SeedNotebooksproTest extends PostgresTestBase {

    @Test
    @DisplayName("NotebooksPro is seeded as a WooCommerce store forced to tecnologia")
    void seedsNotebookspro() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT plataforma, rubro_forzado, monomarca, origen FROM sitio WHERE sitio_key = 'notebookspro'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("plataforma")).isEqualTo("woocommerce");
            assertThat(rs.getString("rubro_forzado")).isEqualTo("tecnologia");
            assertThat(rs.getBoolean("monomarca")).isFalse();
            assertThat(rs.getString("origen")).isEqualTo("config");
        }
    }

    @Test
    @DisplayName("Rolling back removes the NotebooksPro sitio row and nothing else")
    void rollbackIsContained() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                int sitiosAntes = count(st, "SELECT count(*) FROM sitio");

                st.execute(DocumentedRollback.sqlFor("V61"));

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key = 'notebookspro'")).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio")).isEqualTo(sitiosAntes - 1);
            } finally {
                c.rollback();
            }
        }
    }

    private static int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
