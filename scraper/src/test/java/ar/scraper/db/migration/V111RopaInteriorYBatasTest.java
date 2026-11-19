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

@DisplayName("V111 — Sportline underwear leaves and Arredo bath robes: existing categories only, and a contained rollback")
class V111RopaInteriorYBatasTest extends PostgresTestBase {

    private static final String MIGRACION = "/db/migration/V111__sitio_categoria_ropa_interior_y_batas.sql";

    @Test
    @DisplayName("five leaves map to Toalla, Calzoncillos and Bombacha, all in the canon")
    void mapsTheLeaves() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(migracion());
            assertThat(cuantos(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = 'arredo' AND categoria = 'Toalla'")).isEqualTo(3);
            assertThat(cuantos(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = 'sportline' AND origen_id = 1000177 AND categoria = 'Calzoncillos'")).isEqualTo(1);
            assertThat(cuantos(st, "SELECT count(*) FROM sitio_categoria WHERE sitio_key = 'sportline' AND origen_id = 1000178 AND categoria = 'Bombacha'")).isEqualTo(1);
            assertThat(cuantos(st, "SELECT count(*) FROM sitio_categoria s LEFT JOIN categoria k ON k.nombre = s.categoria "
                    + "WHERE s.origen_id IN (15, 61, 62, 1000177, 1000178) AND s.categoria IS NOT NULL AND k.nombre IS NULL")).isZero();
        }
    }

    @Test
    @DisplayName("rolling back removes exactly those five rows")
    void rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(migracion());
                int otras = cuantos(st, "SELECT count(*) FROM sitio_categoria WHERE origen_id NOT IN (15, 61, 62, 1000177, 1000178)");

                st.execute(DocumentedRollback.sqlFor("V111"));

                assertThat(cuantos(st, "SELECT count(*) FROM sitio_categoria WHERE origen_id IN (15, 61, 62, 1000177, 1000178) AND categoria IS NOT NULL")).isZero();
                assertThat(cuantos(st, "SELECT count(*) FROM sitio_categoria WHERE origen_id NOT IN (15, 61, 62, 1000177, 1000178)")).isEqualTo(otras);
            } finally {
                c.rollback();
            }
        }
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V111RopaInteriorYBatasTest.class.getResourceAsStream(MIGRACION))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int cuantos(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
