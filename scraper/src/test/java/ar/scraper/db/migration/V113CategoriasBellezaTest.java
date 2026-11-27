package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V113 — beauty categories")
class V113CategoriasBellezaTest extends PostgresTestBase {

    @Test
    @DisplayName("the three categories exist, so the FK accepts what BellezaClassifier produces")
    void lasCategoriasExisten() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(cuantos(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Maquillaje', 'Cuidado de la Piel', 'Cuidado del Cabello')"))
                    .isEqualTo(3);
        }
    }

    @Test
    @DisplayName("rolling back sends the products to Otros and their site's default rubro, repoints the leaves, then drops the rows")
    void rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                // V138 moved the rubro CHECKs to foreign keys and the categories are referenced: its rollback goes first,
                // before any row is written (a pending catalog_version trigger blocks the ALTER).
                st.execute(DocumentedRollback.sqlFor("V144"));
                st.execute(DocumentedRollback.sqlFor("V143"));
                st.execute(DocumentedRollback.sqlFor("V142"));
                st.execute(DocumentedRollback.sqlFor("V141"));
                st.execute(DocumentedRollback.sqlFor("V140"));
                st.execute(DocumentedRollback.sqlFor("V139"));
                st.execute(DocumentedRollback.sqlFor("V138"));
                st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro) VALUES "
                        + "('https://x.test/labial', 'Arredo', 'Labial Mate', 1000, 'Maquillaje', 'Labios', 'belleza'), "
                        + "('https://x.test/perfume', 'Arredo', 'Perfume EDP', 1000, 'Perfume', 'Eau de Parfum', 'belleza')");
                st.executeUpdate("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria) "
                        + "VALUES ('arredo', 999002, 'Labios', 'Maquillaje')");

                st.execute(DocumentedRollback.sqlFor("V113"));

                assertThat(cuantos(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Maquillaje', 'Cuidado de la Piel', 'Cuidado del Cabello')"))
                        .isZero();
                assertThat(cuantos(st, "SELECT count(*) FROM productos WHERE url = 'https://x.test/labial' "
                        + "AND categoria = 'Otros' AND sub_categoria = '' AND rubro = 'hogar'")).isEqualTo(1);
                assertThat(cuantos(st, "SELECT count(*) FROM productos WHERE url = 'https://x.test/perfume' "
                        + "AND categoria = 'Perfume' AND rubro = 'belleza'")).isEqualTo(1);
                assertThat(cuantos(st, "SELECT count(*) FROM sitio_categoria WHERE origen_id = 999002 AND categoria IS NULL"))
                        .isEqualTo(1);
            } finally {
                c.rollback();
            }
        }
    }

    private static int cuantos(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
