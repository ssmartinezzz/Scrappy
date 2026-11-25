package ar.scraper.db.migration;

import ar.scraper.classification.CategoryGroups;
import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V117 — Pila: the category row, its rubro, and a rollback that leaves nothing pointing at it")
class V117CategoriaPilaTest extends PostgresTestBase {

    @Test
    @DisplayName("the category exists, so the FK accepts what the classifier produces, and it is household")
    void laCategoriaExiste() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(cuantos(st, "SELECT count(*) FROM categoria WHERE nombre = 'Pila'")).isEqualTo(1);
        }
        assertThat(CategoryGroups.canonicalCategories()).contains("Pila");
        assertThat(CategoryGroups.esCategoriaHogar("Pila")).isTrue();
    }

    @Test
    @DisplayName("rolling back repoints the products and the leaves, then drops the row")
    void rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                // V138 moved the rubro CHECKs to foreign keys and the categories are referenced: its rollback goes first,
                // before any row is written (a pending catalog_version trigger blocks the ALTER).
                st.execute(DocumentedRollback.sqlFor("V138"));
                st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro) "
                        + "VALUES ('https://x.test/pila', 'Arredo', 'Pilas Philco AA Alcalina X8', 5000, 'Pila', 'hogar')");
                st.executeUpdate("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria) "
                        + "VALUES ('arredo', 999117, 'Pilas', 'Pila')");

                st.execute(DocumentedRollback.sqlFor("V117"));

                assertThat(cuantos(st, "SELECT count(*) FROM categoria WHERE nombre = 'Pila'")).isZero();
                assertThat(cuantos(st, "SELECT count(*) FROM productos WHERE url = 'https://x.test/pila' AND categoria = 'Otros'"))
                        .isEqualTo(1);
                assertThat(cuantos(st, "SELECT count(*) FROM sitio_categoria WHERE origen_id = 999117 AND categoria IS NULL"))
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
