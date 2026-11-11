package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** The monomarca/marca parity is pinned by V52SitioMonomarcaTest and the platform vocabulary by PlatformVocabularySyncTest. */
@DisplayName("V91 — magento platform and the Las Pepas seed")
class V91LaspepasSeedTest extends PostgresTestBase {

    @Test
    @DisplayName("Las Pepas is seeded on magento: monomarca, not premium, no forced rubro, from config, with its marca row")
    void seededRow() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre, plataforma, es_premium, rubro_forzado, origen, monomarca, "
                     + "(SELECT count(*) FROM marca m WHERE m.nombre = s.nombre) AS marcas "
                     + "FROM sitio s WHERE sitio_key = 'laspepas'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("nombre")).isEqualTo("Laspepas");
            assertThat(rs.getString("plataforma")).isEqualTo("magento");
            assertThat(rs.getBoolean("es_premium")).isFalse();
            assertThat(rs.getString("rubro_forzado")).as("RubroResolver decides by category").isNull();
            assertThat(rs.getString("origen")).isEqualTo("config");
            assertThat(rs.getBoolean("monomarca")).isTrue();
            assertThat(rs.getInt("marcas")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the documented rollback removes the site, its marca and the platform value from the CHECK")
    void rollbackDocumentadoCorre() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V91"));

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key = 'laspepas'")).isZero();
                assertThat(count(st, "SELECT count(*) FROM marca WHERE nombre = 'Laspepas'")).isZero();
                try (ResultSet rs = st.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = 'sitio'::regclass AND conname = 'sitio_plataforma_check'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).doesNotContain("magento").contains("kevingston");
                }
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
