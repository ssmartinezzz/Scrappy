package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V95 — Under Armour and Nike: monobrand VTEX seed whose marca is the brand's own name")
class V95SeedUnderArmourNikeTest extends PostgresTestBase {

    @Test
    @DisplayName("both stores are seeded monomarca on vtex, and their nombre is a marca row")
    void seedsBothStores() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            for (String[] s : new String[][] {{"underarmour", "Under Armour"}, {"nike", "Nike"}}) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT nombre, plataforma, monomarca, origen, rubro_forzado FROM sitio WHERE sitio_key = '" + s[0] + "'")) {
                    assertThat(rs.next()).as(s[0]).isTrue();
                    assertThat(rs.getString("nombre")).isEqualTo(s[1]);
                    assertThat(rs.getString("plataforma")).isEqualTo("vtex");
                    assertThat(rs.getBoolean("monomarca")).isTrue();
                    assertThat(rs.getString("origen")).isEqualTo("config");
                    assertThat(rs.getString("rubro_forzado")).isNull();
                }
                assertThat(count(st, "SELECT count(*) FROM marca WHERE nombre = '" + s[1] + "'")).isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("rolling back removes the two sitio rows and leaves marca alone")
    void rollbackIsContained() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                int sitiosAntes = count(st, "SELECT count(*) FROM sitio");
                int marcasAntes = count(st, "SELECT count(*) FROM marca");

                st.execute(DocumentedRollback.sqlFor("V95"));

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key IN ('underarmour', 'nike')")).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio")).isEqualTo(sitiosAntes - 2);
                assertThat(count(st, "SELECT count(*) FROM marca")).isEqualTo(marcasAntes);
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
