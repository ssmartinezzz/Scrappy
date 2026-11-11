package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V86 seeds four own-label stores and V87 adds the 'kevingston' platform plus its seed. The monomarca/marca
 * parity and the platform vocabulary are pinned by V52SitioMonomarcaTest and PlatformVocabularySyncTest; this
 * class pins the seeded values and the two documented rollbacks.
 */
@DisplayName("V86 / V87 — seeded values of the five fashion sites and their documented rollbacks")
class V86V87SeedFashionSitesTest extends PostgresTestBase {

    @ParameterizedTest(name = "{0} -> {2} / {1}")
    @CsvSource({
        "oneplaceworld,Oneplaceworld,tiendanube",
        "burtonargentina,Burtonargentina,tiendanube",
        "kingofthekongo,Kingofthekongo,tiendanube",
        "equus,Equus,vtex",
        "kevingston,Kevingston,kevingston",
    })
    @DisplayName("each site is seeded monomarca, not premium, no forced rubro, from config, with its marca row")
    void seededRow(String key, String nombre, String plataforma) throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre, plataforma, es_premium, rubro_forzado, origen, monomarca, "
                     + "(SELECT count(*) FROM marca m WHERE m.nombre = s.nombre) AS marcas "
                     + "FROM sitio s WHERE sitio_key = '" + key + "'")) {
            assertThat(rs.next()).as("seed row of " + key).isTrue();
            assertThat(rs.getString("nombre")).isEqualTo(nombre);
            assertThat(rs.getString("plataforma")).isEqualTo(plataforma);
            assertThat(rs.getBoolean("es_premium")).isFalse();
            assertThat(rs.getString("rubro_forzado")).as("RubroResolver decides by category").isNull();
            assertThat(rs.getString("origen")).isEqualTo("config");
            assertThat(rs.getBoolean("monomarca")).isTrue();
            assertThat(rs.getInt("marcas")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("V86 rollback removes the four sites and their marcas, and leaves kevingston alone")
    void v86RollbackDocumentadoCorre() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V86"));

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key IN "
                        + "('oneplaceworld','burtonargentina','kingofthekongo','equus')")).isZero();
                assertThat(count(st, "SELECT count(*) FROM marca WHERE nombre IN "
                        + "('Oneplaceworld','Burtonargentina','Kingofthekongo','Equus')")).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key = 'kevingston'")).isEqualTo(1);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("V87 rollback removes kevingston, its marca and the platform value from the CHECK")
    void v87RollbackDocumentadoCorre() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                // Newest first: V91 widened the CHECK again and seeded a 'magento' row the narrower one would reject.
                st.execute(DocumentedRollback.sqlFor("V91"));
                st.execute(DocumentedRollback.sqlFor("V87"));

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key = 'kevingston'")).isZero();
                assertThat(count(st, "SELECT count(*) FROM marca WHERE nombre = 'Kevingston'")).isZero();
                try (ResultSet rs = st.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = 'sitio'::regclass AND conname = 'sitio_plataforma_check'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).doesNotContain("kevingston").contains("hadriatica");
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
