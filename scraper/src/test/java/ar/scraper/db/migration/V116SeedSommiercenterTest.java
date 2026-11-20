package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V116 — Sommier Center: magento seed defaulting to hogar, and a contained rollback")
class V116SeedSommiercenterTest extends PostgresTestBase {

    @Test
    @DisplayName("Sommier Center is a magento row, not monomarca, no forced rubro, defaulting to hogar")
    void seedsTheSite() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre, plataforma, monomarca, origen, rubro_forzado, rubro_por_defecto "
                     + "FROM sitio WHERE sitio_key = 'sommiercenter'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("nombre")).isEqualTo("Sommiercenter");
            assertThat(rs.getString("plataforma")).isEqualTo("magento");
            assertThat(rs.getString("origen")).isEqualTo("config");
            assertThat(rs.getBoolean("monomarca")).isFalse();
            assertThat(rs.getString("rubro_forzado")).isNull();
            assertThat(rs.getString("rubro_por_defecto")).isEqualTo("hogar");
        }
    }

    @Test
    @DisplayName("the documented rollback removes the site and leaves the platform vocabulary alone")
    void rollbackDocumentadoCorre() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V116"));
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM sitio WHERE sitio_key = 'sommiercenter'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
                try (ResultSet rs = st.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = 'sitio'::regclass AND conname = 'sitio_plataforma_check'")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString(1)).contains("magento");
                }
            } finally {
                c.rollback();
            }
        }
    }
}
