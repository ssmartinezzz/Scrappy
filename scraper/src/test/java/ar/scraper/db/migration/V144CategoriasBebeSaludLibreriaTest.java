package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Epic("Base de datos")
@Feature("Categorías nuevas")
@DisplayName("V144 — Artículo de Bebé, Salud y Librería: las filas de taxonomía, sitio_categoria.sub_categoria y el rollback")
class V144CategoriasBebeSaludLibreriaTest extends PostgresTestBase {

    private static final String MIGRACION = "/db/migration/V144__categorias_bebe_salud_libreria.sql";
    private static final String URL = "https://v144.test/";
    private static final String SQLSTATE_CHECK = "23514";

    private interface Cuerpo {
        void correr(Statement st) throws Exception;
    }

    @Test
    @DisplayName("each category exists with the rubro of its shelf, no index of its own and no pisa_forzado")
    void taxonomyRows() throws Exception {
        enTransaccion(st -> {
            assertThat(text(st, "SELECT string_agg(categoria || '=' || rubro || '/' || pisa_forzado, ',' ORDER BY categoria) FROM categoria_rubro "
                    + "WHERE categoria IN ('Artículo de Bebé', 'Salud', 'Librería') AND sub_categoria = ''"))
                    .isEqualTo("Artículo de Bebé=juguetes/false,Librería=oficina/false,Salud=belleza/false");
            assertThat(scalar(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Artículo de Bebé', 'Salud', 'Librería') AND indice IS NULL")).isEqualTo(3);
            st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro) VALUES ('" + URL + "fk', 'v144', 'Cochecito', 1, 'Artículo de Bebé', 'juguetes')");
            assertThat(scalar(st, "SELECT count(*) FROM productos WHERE url = '" + URL + "fk'")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("sub_categoria of sitio_categoria is nullable text and the CHECK refuses a sub without a category or a blank one")
    void subOfALeaf() throws Exception {
        enTransaccion(st -> {
            assertThat(text(st, "SELECT is_nullable || '/' || data_type FROM information_schema.columns "
                    + "WHERE table_name = 'sitio_categoria' AND column_name = 'sub_categoria'")).isEqualTo("YES/text");
            st.executeUpdate("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, es_servicio) VALUES ('v144', 1, 'Freezers', false)");

            st.execute("SAVEPOINT sin_categoria");
            assertThatThrownBy(() -> st.executeUpdate("UPDATE sitio_categoria SET sub_categoria = 'Freezer' WHERE sitio_key = 'v144'"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(SQLSTATE_CHECK));
            st.execute("ROLLBACK TO SAVEPOINT sin_categoria");
            assertThatThrownBy(() -> st.executeUpdate("UPDATE sitio_categoria SET categoria = 'Heladera', sub_categoria = ' ' WHERE sitio_key = 'v144'"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(SQLSTATE_CHECK));
            st.execute("ROLLBACK TO SAVEPOINT sin_categoria");

            st.executeUpdate("UPDATE sitio_categoria SET categoria = 'Heladera', sub_categoria = 'Freezer' WHERE sitio_key = 'v144'");
            assertThat(text(st, "SELECT sub_categoria FROM sitio_categoria WHERE sitio_key = 'v144'")).isEqualTo("Freezer");
        });
    }

    @Test
    @DisplayName("running it twice changes nothing the second time")
    void rerunnable() throws Exception {
        enTransaccion(st -> {
            st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro) VALUES ('" + URL + "heladera', 'v144', 'Freezer Horizontal Gafa 198 L', 1, 'Heladera', 'electrodomesticos')");

            st.execute(migracion());
            st.execute(migracion());

            assertThat(text(st, "SELECT sub_categoria FROM productos WHERE url = '" + URL + "heladera'")).isEqualTo("Freezer");
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE categoria IN ('Artículo de Bebé', 'Salud', 'Librería')")).isEqualTo(3);
            assertThat(scalar(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Artículo de Bebé', 'Salud', 'Librería')")).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("catalog_version moves exactly once, however many rows the migration rewrites")
    void bumpsTheCatalogVersionOnce() throws Exception {
        enTransaccion(st -> {
            for (int i = 0; i < 5; i++) {
                st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro) VALUES ('" + URL + "bump/" + i
                        + "', 'v144', 'Termotanque Electrico Longvie Te80d " + i + "', 1, 'Termotanque', 'electrodomesticos')");
            }
            st.execute("SET CONSTRAINTS ALL IMMEDIATE");
            st.execute("SELECT set_config('catalog_version.bumped_txid', '', true)");
            long antes = scalar(st, "SELECT version FROM catalog_version");
            st.execute("SET CONSTRAINTS ALL DEFERRED");

            st.execute(migracion());
            st.execute("SET CONSTRAINTS ALL IMMEDIATE");

            assertThat(scalar(st, "SELECT version FROM catalog_version")).isEqualTo(antes + 1);
        });
    }

    @Test
    @DisplayName("the documented rollback sends the rows of the three categories back to Otros with the rubro the site gives, drops the column, the rows and the Flyway row")
    void rollbackDocumentado() throws Exception {
        enTransaccion(st -> {
            st.executeUpdate("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES ('v144f', 'v144f', 'vtex', false, 'tecnologia', 'config')");
            st.executeUpdate("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen, rubro_por_defecto) VALUES ('v144d', 'v144d', 'vtex', false, NULL, 'config', 'hogar')");
            fila(st, "bebe", "Cochecito Oxford", "Artículo de Bebé", "juguetes", "v144");
            fila(st, "salud", "Tensiómetro Aneroide", "Salud", "belleza", "v144f");
            fila(st, "libreria", "Cuaderno Rivadavia", "Librería", "oficina", "v144d");
            fila(st, "libre", "Cuaderno Rivadavia", "Librería", "oficina", "v144");
            fila(st, "heladera", "Freezer Horizontal Gafa 198 L", "Heladera", "electrodomesticos", "v144");
            st.executeUpdate("UPDATE productos SET sub_categoria = 'Freezer' WHERE url = '" + URL + "heladera'");
            st.executeUpdate("INSERT INTO sitio_categoria (sitio_key, origen_id, nombre, es_servicio, categoria, sub_categoria) VALUES ('v144', 7, 'Cuadernos', false, 'Librería', NULL)");

            st.execute(DocumentedRollback.sqlFor("V144"));

            assertThat(text(st, "SELECT categoria || '/' || rubro FROM productos WHERE url = '" + URL + "bebe'")).isEqualTo("Otros/indumentaria");
            assertThat(text(st, "SELECT categoria || '/' || rubro FROM productos WHERE url = '" + URL + "salud'")).as("a forced site keeps its rubro").isEqualTo("Otros/tecnologia");
            assertThat(text(st, "SELECT categoria || '/' || rubro FROM productos WHERE url = '" + URL + "libreria'")).as("the site's default").isEqualTo("Otros/hogar");
            assertThat(text(st, "SELECT categoria || '/' || rubro FROM productos WHERE url = '" + URL + "libre'")).isEqualTo("Otros/indumentaria");
            assertThat(text(st, "SELECT categoria || '/' || sub_categoria FROM productos WHERE url = '" + URL + "heladera'")).isEqualTo("Heladera/");
            assertThat(text(st, "SELECT coalesce(categoria, 'null') FROM sitio_categoria WHERE sitio_key = 'v144' AND origen_id = 7")).isEqualTo("null");
            assertThat(scalar(st, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'sitio_categoria' AND column_name = 'sub_categoria'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Artículo de Bebé', 'Salud', 'Librería')")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM categoria_rubro WHERE categoria IN ('Artículo de Bebé', 'Salud', 'Librería')")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '144'")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '143'")).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("the rollback of V144 composes with the one of V143: newest first, and both run")
    void chainsWithV143() throws Exception {
        enTransaccion(st -> {
            st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, rubro) VALUES ('" + URL
                    + "reloj', 'v144', 'RELOJ CASIO F-91W-1D', 1, 'Reloj', 'analógico', 'indumentaria')");

            st.execute(DocumentedRollback.sqlFor("V144"));
            st.execute(DocumentedRollback.sqlFor("V143"));

            assertThat(text(st, "SELECT sub_categoria || '/' || rubro FROM productos WHERE url = '" + URL + "reloj'")).isEqualTo("/tecnologia");
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version IN ('143', '144')")).isZero();
            assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '142'")).isEqualTo(1);
        });
    }

    private static void fila(Statement st, String sufijo, String nombre, String categoria, String rubro, String sitio) throws SQLException {
        st.executeUpdate("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro) VALUES ('" + URL + sufijo + "', '" + sitio + "', '"
                + nombre + "', 1000, '" + categoria + "', '" + rubro + "')");
    }

    private void enTransaccion(Cuerpo cuerpo) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                if (scalar(st, "SELECT count(*) FROM sitio WHERE sitio_key = 'v144'") == 0) {
                    st.executeUpdate("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES ('v144', 'v144', 'vtex', false, NULL, 'config') ON CONFLICT DO NOTHING");
                }
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static String text(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String migracion() throws IOException {
        try (InputStream in = Objects.requireNonNull(V144CategoriasBebeSaludLibreriaTest.class.getResourceAsStream(MIGRACION))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
