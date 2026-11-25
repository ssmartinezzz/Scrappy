package ar.scraper.db.migration;

import ar.scraper.classification.CategoryGroups;
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

@DisplayName("V127 — Accesorio Apple: the category row, the backfill of stored rows, and a rollback that leaves nothing pointing at it")
class V127CategoriaAccesorioAppleTest extends PostgresTestBase {

    @Test
    @DisplayName("the category exists, so the FK accepts what the classifier produces, and it is technology")
    void laCategoriaExiste() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(cuantos(st, "SELECT count(*) FROM categoria WHERE nombre = 'Accesorio Apple'")).isEqualTo(1);
        }
        assertThat(CategoryGroups.canonicalCategories()).contains("Accesorio Apple");
        assertThat(CategoryGroups.esCategoriaTech("Accesorio Apple")).isTrue();
    }

    private static void fila(Statement st, String n, String sitio, String nombre, String categoria, String rubro) throws Exception {
        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro) VALUES ('https://x.test/" + n + "', '"
                + sitio + "', '" + nombre + "', 1000, '" + categoria + "', '" + rubro + "')");
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V127CategoriaAccesorioAppleTest.class
                .getResourceAsStream("/db/migration/V127__categoria_accesorio_apple.sql"))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String celda(Statement st, String n, String columna) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT " + columna + " FROM productos WHERE url = 'https://x.test/" + n + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("the backfill moves exactly the stored rows the classifier rule claims, with their sub and rubro")
    void backfill() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                fila(st, "ipad", "Macstation", "Smart Folio de Apple para iPad Air 11\" (M4) - Azul denim", "Jean", "indumentaria");
                fila(st, "mac", "Macstation", "Funda Carry Zip de Incase para MacBook 13\"", "Notebook", "tecnologia");
                fila(st, "airpods", "Macstation", "Funda Hybrid con MagSafe para AirPods 4", "Celular", "tecnologia");
                fila(st, "reloj", "Macstation", "Correa deportiva naranja de Apple para caja de 40 mm - M/L", "Reloj", "tecnologia");
                fila(st, "watch", "Oncity", "Funda Con Templado 360 Para Apple Watch 46mm", "Reloj", "tecnologia");
                fila(st, "pencil", "Oncity", "Apple Pencil Pro", "Tablet", "tecnologia");
                fila(st, "airtag", "Macstation", "AirTag 2 (1 unidad)", "Otros", "tecnologia");
                fila(st, "forzado", "Entreno", "Funda para iPad 10ma Gen", "Otros", "suplementos");
                fila(st, "bloqueado", "Macstation", "Funda para iPad mini", "Otros", "tecnologia");
                st.executeUpdate("UPDATE productos SET bloqueado_por = 'qa', bloqueado_at = now() WHERE url = 'https://x.test/bloqueado'");
                // stay where they are
                fila(st, "iphone", "Macstation", "Funda con MagSafe de Apple para iPhone 16 Pro", "Celular", "tecnologia");
                fila(st, "ambos", "Macstation", "Cargador Funda para iPhone y iPad", "Otros", "tecnologia");
                fila(st, "generica", "Oncity", "Funda Universal para Notebook 15.6 Pulgadas", "Otros", "tecnologia");
                fila(st, "dispositivo", "Macstation", "iPad Air 11\" (M4) Wi-Fi", "Tablet", "tecnologia");
                fila(st, "teclado", "Macstation", "Magic Keyboard de Apple para iPad Air 11\"", "Teclado", "tecnologia");
                fila(st, "llavero", "Macstation", "Llavero de FineWoven de Apple para AirTag", "Llavero", "tecnologia");

                st.execute(migracion());

                assertThat(celda(st, "ipad", "categoria || '|' || sub_categoria || '|' || rubro")).isEqualTo("Accesorio Apple|iPad|tecnologia");
                assertThat(celda(st, "mac", "categoria || '|' || sub_categoria")).isEqualTo("Accesorio Apple|MacBook");
                assertThat(celda(st, "airpods", "categoria || '|' || sub_categoria")).isEqualTo("Accesorio Apple|AirPods");
                assertThat(celda(st, "reloj", "categoria || '|' || sub_categoria")).isEqualTo("Accesorio Apple|Apple Watch");
                assertThat(celda(st, "watch", "categoria || '|' || sub_categoria")).isEqualTo("Accesorio Apple|Apple Watch");
                assertThat(celda(st, "pencil", "categoria || '|' || sub_categoria")).isEqualTo("Accesorio Apple|iPad");
                assertThat(celda(st, "airtag", "categoria || '|' || sub_categoria")).isEqualTo("Accesorio Apple|");
                assertThat(celda(st, "forzado", "categoria || '|' || rubro")).as("a forced rubro is kept").isEqualTo("Accesorio Apple|suplementos");
                assertThat(celda(st, "bloqueado", "categoria")).as("a row locked by hand is not touched").isEqualTo("Otros");
                for (String n : new String[] {"iphone", "ambos", "generica", "dispositivo", "teclado", "llavero"})
                    assertThat(celda(st, n, "categoria")).as(n).isNotEqualTo("Accesorio Apple");

                st.execute(migracion());
                assertThat(celda(st, "ipad", "categoria || '|' || sub_categoria")).as("idempotent").isEqualTo("Accesorio Apple|iPad");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("rolling back repoints the products and the leaves, then drops the row")
    void rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                // V138 moved the rubro CHECKs to foreign keys and the categories are referenced: its rollback goes first,
                // before any row is written (a pending catalog_version trigger blocks the ALTER).
                st.execute(DocumentedRollback.sqlFor("V140"));
                st.execute(DocumentedRollback.sqlFor("V139"));
                st.execute(DocumentedRollback.sqlFor("V138"));
                fila(st, "aa", "Macstation", "Funda para iPad Pro", "Accesorio Apple", "tecnologia");
                st.executeUpdate("UPDATE productos SET sub_categoria = 'iPad' WHERE url = 'https://x.test/aa'");
                st.executeUpdate("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, categoria) "
                        + "VALUES ('arredo', 999127, 'Accesorios iPad', 'Accesorio Apple')");

                st.execute(DocumentedRollback.sqlFor("V127"));

                assertThat(cuantos(st, "SELECT count(*) FROM categoria WHERE nombre = 'Accesorio Apple'")).isZero();
                assertThat(cuantos(st, "SELECT count(*) FROM productos WHERE url = 'https://x.test/aa' AND categoria = 'Otros' AND sub_categoria = ''"))
                        .isEqualTo(1);
                assertThat(cuantos(st, "SELECT count(*) FROM sitio_categoria WHERE origen_id = 999127 AND categoria IS NULL"))
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
