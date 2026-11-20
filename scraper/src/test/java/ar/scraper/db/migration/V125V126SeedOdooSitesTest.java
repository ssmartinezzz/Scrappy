package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** The platform value is pinned by V124PlatformOdooTest; the per-site seed sync by SitioSeedSyncTest. */
@DisplayName("V125 / V126 — Macstation and Gentech: odoo seeds, contained rollbacks")
class V125V126SeedOdooSitesTest extends PostgresTestBase {

    private record Fila(String nombre, String plataforma, boolean monomarca, String origen, String rubroForzado,
                        String rubroPorDefecto) {}

    private static Fila fila(Statement st, String key) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT nombre, plataforma, monomarca, origen, rubro_forzado, rubro_por_defecto "
                + "FROM sitio WHERE sitio_key = '" + key + "'")) {
            assertThat(rs.next()).isTrue();
            return new Fila(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4), rs.getString(5), rs.getString(6));
        }
    }

    private static int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("Macstation is an odoo config row, not monomarca, tecnologia by default")
    void seedsMacstation() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(fila(st, "macstation")).isEqualTo(new Fila("Macstation", "odoo", false, "config", null, "tecnologia"));
        }
    }

    @Test
    @DisplayName("Gentech is an odoo config row, monomarca, suplementos by default, with its marca row")
    void seedsGentech() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(fila(st, "gentech")).isEqualTo(new Fila("Gentech", "odoo", true, "config", null, "suplementos"));
            assertThat(count(st, "SELECT count(*) FROM marca WHERE nombre = 'Gentech'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("rolling back V126 then V125 removes each site and nothing else; V124 then narrows the CHECK")
    void rollbacksAreContained() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                int sitiosAntes = count(st, "SELECT count(*) FROM sitio");

                st.execute(DocumentedRollback.sqlFor("V126"));
                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key = 'gentech'")).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key = 'macstation'")).isEqualTo(1);

                st.execute(DocumentedRollback.sqlFor("V125"));
                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key = 'macstation'")).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio")).isEqualTo(sitiosAntes - 2);
                assertThat(count(st, "SELECT count(*) FROM sitio WHERE plataforma = 'odoo'")).isZero();
            } finally {
                c.rollback();
            }
        }
    }
}
