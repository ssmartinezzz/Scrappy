package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V98 — Ayrun: Tiendanube own-label seed, its marca row, and a contained rollback")
class V98SeedAyrunTest extends PostgresTestBase {

    @Test
    @DisplayName("Ayrun is seeded as a monomarca Tiendanube store with its marca row")
    void seedsAyrun() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT nombre, plataforma, monomarca, origen FROM sitio WHERE sitio_key = 'ayrun'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("nombre")).isEqualTo("Ayrun");
                assertThat(rs.getString("plataforma")).isEqualTo("tiendanube");
                assertThat(rs.getBoolean("monomarca")).isTrue();
                assertThat(rs.getString("origen")).isEqualTo("config");
            }
            assertThat(count(st, "SELECT count(*) FROM marca WHERE nombre = 'Ayrun'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Rolling back removes the Ayrun sitio and marca rows and nothing else")
    void rollbackIsContained() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                int sitiosAntes = count(st, "SELECT count(*) FROM sitio");

                st.execute(DocumentedRollback.sqlFor("V98"));

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key = 'ayrun'")).isZero();
                assertThat(count(st, "SELECT count(*) FROM marca WHERE nombre = 'Ayrun'")).isZero();
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
