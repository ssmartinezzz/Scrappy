package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("V74 — gaming/collectible categories, the Hadriatica seed, the juguetes default rubro and the rollback")
class V74HadriaticaTest extends PostgresTestBase {

    private static final String CINCO = "('Videojuego','Consola','Figura Coleccionable','Peluche','Juego de Mesa','Libro')";

    @Test
    @DisplayName("V74 inserts the six categories")
    void seedsFiveCategories() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(count(st, "SELECT count(*) FROM categoria WHERE nombre IN " + CINCO)).isEqualTo(6);
        }
    }

    @Test
    @DisplayName("V74 seeds Hadriatica as an unforced Tiendanube site whose default rubro is juguetes")
    void seedsHadriatica() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT nombre, plataforma, rubro_forzado, rubro_por_defecto, monomarca, origen "
                     + "FROM sitio WHERE sitio_key = 'hadriatica'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("nombre")).isEqualTo("Hadriatica");
            assertThat(rs.getString("plataforma")).as("V76 moves it to its own page").isEqualTo("hadriatica");
            assertThat(rs.getString("rubro_forzado")).isNull();
            assertThat(rs.getString("rubro_por_defecto")).isEqualTo("juguetes");
            assertThat(rs.getBoolean("monomarca")).isFalse();
            assertThat(rs.getString("origen")).isEqualTo("config");
        }
    }

    @Test
    @DisplayName("rubro_por_defecto accepts juguetes and still rejects anything outside the domain")
    void defaultRubroDomain() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE sitio SET rubro_por_defecto = 'juguetes' WHERE sitio_key = 'easy'");
                st.executeUpdate("UPDATE sitio SET rubro_por_defecto = 'hogar' WHERE sitio_key = 'easy'");
                assertThatThrownBy(() -> st.executeUpdate(
                        "UPDATE sitio SET rubro_por_defecto = 'indumentaria' WHERE sitio_key = 'easy'"))
                        .hasMessageContaining("sitio_rubro_por_defecto_check");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("V74 rollback removes the site and narrows the domain back")
    void rollbackRemovesSiteAndNarrowsDomain() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                int sitiosAntes = count(st, "SELECT count(*) FROM sitio");

                st.execute(DocumentedRollback.sqlFor("V74"));

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key = 'hadriatica'")).isZero();
                assertThat(count(st, "SELECT count(*) FROM sitio")).isEqualTo(sitiosAntes - 1);
                assertThatThrownBy(() -> st.executeUpdate(
                        "UPDATE sitio SET rubro_por_defecto = 'juguetes' WHERE sitio_key = 'easy'"))
                        .hasMessageContaining("sitio_rubro_por_defecto_check");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("With scraped products the rollback keeps the site row but drops its juguetes default so the domain can narrow")
    void rollbackKeepsASiteThatHasProducts() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                insertProduct(st, "https://rollback.test/peluche", "Peluche");

                st.execute(DocumentedRollback.sqlFor("V74"));

                assertThat(count(st, "SELECT count(*) FROM sitio WHERE sitio_key = 'hadriatica' "
                        + "AND rubro_por_defecto IS NULL")).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM productos WHERE url = 'https://rollback.test/peluche'"))
                        .isEqualTo(1);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("V74 rollback removes exactly the six categories and leaves live products with a NULL category")
    void rollbackRemovesFiveCategories() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                insertProduct(st, "https://rollback.test/peluche", "Peluche");
                int antes = count(st, "SELECT count(*) FROM categoria");

                st.execute(DocumentedRollback.sqlFor("V74"));

                assertThat(antes - count(st, "SELECT count(*) FROM categoria")).isEqualTo(6);
                assertThat(count(st, "SELECT count(*) FROM categoria WHERE nombre IN " + CINCO)).isZero();
                assertThat(count(st, "SELECT count(*) FROM productos WHERE url = 'https://rollback.test/peluche' "
                        + "AND categoria IS NULL")).isEqualTo(1);
                assertThat(count(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Juguete','Reloj','Joystick')"))
                        .isEqualTo(3);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("productos.rubro accepts libros; the rollback sets those rows to NULL and narrows the domain to 8 values")
    void librosRubroDomain() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("""
                        INSERT INTO productos (url, sitio, nombre, precio, rubro, categoria)
                        VALUES ('https://rollback.test/libro', 'Hadriatica', 'ONE PIECE 003 - IVREA', 1000, 'libros', 'Libro')
                        """);
                st.execute("SET CONSTRAINTS ALL IMMEDIATE");

                st.execute(DocumentedRollback.sqlFor("V74"));

                assertThat(count(st, "SELECT count(*) FROM productos WHERE url = 'https://rollback.test/libro' "
                        + "AND rubro IS NULL AND categoria IS NULL")).isEqualTo(1);
                assertThatThrownBy(() -> st.executeUpdate("""
                        INSERT INTO productos (url, sitio, nombre, precio, rubro)
                        VALUES ('https://rollback.test/otro', 'Hadriatica', 'x', 1000, 'libros')
                        """)).hasMessageContaining("chk_productos_rubro_domain");
            } finally {
                c.rollback();
            }
        }
    }

    private static void insertProduct(Statement st, String url, String categoria) throws Exception {
        st.executeUpdate("""
                INSERT INTO productos (url, sitio, nombre, precio, rubro, categoria)
                VALUES ('%s', 'Hadriatica', 'Producto de prueba', 1000, 'juguetes', '%s')
                """.formatted(url, categoria));
        st.execute("SET CONSTRAINTS ALL IMMEDIATE");
    }

    private static int count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
