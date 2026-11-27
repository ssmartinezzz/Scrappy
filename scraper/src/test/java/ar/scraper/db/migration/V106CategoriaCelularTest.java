package ar.scraper.db.migration;

import ar.scraper.classification.CategoryGroups;
import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V106 — Celular: the category row, its rubro, and a rollback that leaves nothing pointing at it")
class V106CategoriaCelularTest extends PostgresTestBase {

    @Test
    @DisplayName("the category exists, so the FK accepts what the classifier produces, and it has its own group (celulares since V140)")
    void laCategoriaExiste() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(cuantos(st, "SELECT count(*) FROM categoria WHERE nombre = 'Celular'")).isEqualTo(1);
        }
        assertThat(CategoryGroups.canonicalCategories()).contains("Celular");
        assertThat(CategoryGroups.esCategoriaCelulares("Celular")).isTrue();
    }

    @Test
    @DisplayName("rolling back repoints the products and the leaves, then drops the row")
    void rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                // V138 moved the rubro CHECKs to foreign keys and the categories are referenced: its rollback goes first,
                // before any row is written (a pending catalog_version trigger blocks the ALTER).
                st.execute(DocumentedRollback.sqlFor("V145"));
                st.execute(DocumentedRollback.sqlFor("V144"));
                st.execute(DocumentedRollback.sqlFor("V143"));
                st.execute(DocumentedRollback.sqlFor("V142"));
                st.execute(DocumentedRollback.sqlFor("V141"));
                st.execute(DocumentedRollback.sqlFor("V140"));
                st.execute(DocumentedRollback.sqlFor("V139"));
                st.execute(DocumentedRollback.sqlFor("V138"));
                st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro) "
                        + "VALUES ('https://x.test/celular', 'Arredo', 'Celular Samsung Galaxy S25+', 2199999, 'Celular', 'tecnologia')");
                st.executeUpdate("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria) "
                        + "VALUES ('arredo', 999001, 'Celulares', 'Celular')");

                st.execute(DocumentedRollback.sqlFor("V106"));

                assertThat(cuantos(st, "SELECT count(*) FROM categoria WHERE nombre = 'Celular'")).isZero();
                assertThat(cuantos(st, "SELECT count(*) FROM productos WHERE url = 'https://x.test/celular' AND categoria = 'Otros'"))
                        .isEqualTo(1);
                assertThat(cuantos(st, "SELECT count(*) FROM sitio_categoria WHERE origen_id = 999001 AND categoria IS NULL"))
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
