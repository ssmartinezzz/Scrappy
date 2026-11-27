package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V145 — Fravega: VTEX seed defaulting to electrodomesticos, curated leaves with their subs, and a contained rollback")
class V145SeedFravegaTest extends PostgresTestBase {

    private static final String MIGRACION = "/db/migration/V145__seed_fravega.sql";
    private static final String SITIO = "sitio_key = 'fravega'";

    @Test
    @DisplayName("Fravega is a vtex row, not monomarca, not forced, defaulting to electrodomesticos")
    void seedsTheSite() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre, plataforma, monomarca, origen, rubro_forzado, rubro_por_defecto "
                     + "FROM sitio WHERE " + SITIO)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("nombre")).isEqualTo("Fravega");
            assertThat(rs.getString("plataforma")).isEqualTo("vtex");
            assertThat(rs.getString("origen")).isEqualTo("config");
            assertThat(rs.getBoolean("monomarca")).isFalse();
            assertThat(rs.getString("rubro_forzado")).isNull();
            assertThat(rs.getString("rubro_por_defecto")).isEqualTo("electrodomesticos");
        }
    }

    @Test
    @DisplayName("every curated leaf has a category that exists, a sub never blank, and a sub only with a category")
    void seedsTheLeaves() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute(migracion());
            assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE " + SITIO)).isEqualTo(360);
            assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE " + SITIO + " AND sub_categoria IS NOT NULL")).isEqualTo(123);
            assertThat(count(st, "SELECT count(*) FROM sitio_categoria s LEFT JOIN categoria k ON k.nombre = s.categoria "
                    + "WHERE s." + SITIO + " AND k.nombre IS NULL")).isZero();
            assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE " + SITIO
                    + " AND (categoria IS NULL OR btrim(categoria) = '' OR sub_categoria = '' OR nombre = '')")).isZero();
            c.rollback();
        }
    }

    @Test
    @DisplayName("a leaf carries its category and sub, and a leaf the map leaves blank has no row")
    void leavesCarryTheirSub() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute(migracion());
            assertThat(fila(st, 1000183)).isEqualTo("Freezers|Heladera|Freezer");
            assertThat(fila(st, 1000954)).isEqualTo("Heladeras|Heladera|Heladera");
            assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE " + SITIO + " AND origen_id IN (1000493, 1000807, 1000621, 1000809, 1000808, 1001134)")).isZero();
            c.rollback();
        }
    }

    @Test
    @DisplayName("rolling back removes the site, its leaves, and nothing else")
    void rollbackIsContained() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(migracion());
                int sitiosAntes = count(st, "SELECT count(*) FROM sitio");
                int hojasAjenas = count(st, "SELECT count(*) FROM sitio_categoria WHERE NOT (" + SITIO + ")");

                st.execute(DocumentedRollback.sqlFor("V145"));

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE " + SITIO)).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria WHERE " + SITIO)).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio")).isEqualTo(sitiosAntes - 1);
                assertThat(count(st, "SELECT count(*) FROM sitio_categoria")).isEqualTo(hojasAjenas);
            } finally {
                c.rollback();
            }
        }
    }

    private static String fila(Statement st, int origenId) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT nombre || '|' || categoria || '|' || COALESCE(sub_categoria, '') "
                + "FROM sitio_categoria WHERE " + SITIO + " AND origen_id = " + origenId)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V145SeedFravegaTest.class.getResourceAsStream(MIGRACION))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
