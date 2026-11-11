package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V94 — Vincha, Bandana and Traje Deportivo; Funko is a toy; LEGO subcategory")
class V94VinchaBandanaTrajeDeportivoTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V94__categorias_vincha_bandana_traje_deportivo.sql";

    @Test
    @DisplayName("the three categoria rows exist; the documented rollback removes them and restores Gorra / Traje")
    void categoriasYRollback() throws Exception {
        enTransaccion(st -> {
            assertThat(uno(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Vincha', 'Bandana', 'Traje Deportivo')")).isEqualTo("3");
            st.execute("""
                    INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) VALUES
                        ('v', 'V94 Test', 'Vincha Adidas',   1, 'Vincha',          'indumentaria', true),
                        ('b', 'V94 Test', 'Monky Bandana',   1, 'Bandana',         'indumentaria', true),
                        ('t', 'V94 Test', 'Tracksuit Mocha', 1, 'Traje Deportivo', 'indumentaria', true)
                    """);

            st.execute(DocumentedRollback.sqlFor("V94"));

            assertThat(uno(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Vincha', 'Bandana', 'Traje Deportivo')")).isEqualTo("0");
            assertThat(fila(st, "v")).isEqualTo("Gorra/indumentaria");
            assertThat(fila(st, "b")).isEqualTo("Gorra/indumentaria");
            assertThat(fila(st, "t")).isEqualTo("Traje/indumentaria");
        });
    }

    @Test
    @DisplayName("stored vinchas and bandanas leave Gorra; real gorras and locked rows stay")
    void vinchasYBandanas() throws Exception {
        enTransaccion(st -> {
            st.execute("""
                    INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo, bloqueado_por, bloqueado_at) VALUES
                        ('v1', 'V94 Test', 'Vincha Under Armour Performance', 1, 'Gorra', 'indumentaria', true, NULL, NULL),
                        ('v2', 'V94 Test', 'Vinchas Under Armour',             1, 'Gorra', 'indumentaria', true, NULL, NULL),
                        ('v3', 'V94 Test', 'Vincha Wilson Bandana',            1, 'Gorra', 'indumentaria', true, NULL, NULL),
                        ('b1', 'V94 Test', 'Monky Bandana',                    1, 'Gorra', 'indumentaria', true, NULL, NULL),
                        ('g1', 'V94 Test', 'Gorra Trucker Negra',              1, 'Gorra', 'indumentaria', true, NULL, NULL),
                        ('k1', 'V94 Test', 'Vincha Bloqueada',                 1, 'Gorra', 'indumentaria', true, 'local', now())
                    """);
            aplicar(st);

            assertThat(fila(st, "v1")).isEqualTo("Vincha/indumentaria");
            assertThat(fila(st, "v2")).isEqualTo("Vincha/indumentaria");
            assertThat(fila(st, "v3")).as("the leading noun wins").isEqualTo("Vincha/indumentaria");
            assertThat(fila(st, "b1")).isEqualTo("Bandana/indumentaria");
            assertThat(fila(st, "g1")).isEqualTo("Gorra/indumentaria");
            assertThat(fila(st, "k1")).isEqualTo("Gorra/indumentaria");
        });
    }

    @Test
    @DisplayName("tracksuit / catsuit sets become Traje Deportivo; loose pieces go back to their leading noun; idempotent")
    void trajesDeportivos() throws Exception {
        enTransaccion(st -> {
            st.execute("""
                    INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, sub_categoria, activo, bloqueado_por, bloqueado_at) VALUES
                        ('t1', 'V94 Test', 'Tracksuit Stripe Mocha',                   1, 'Traje',    'indumentaria', '',         true, NULL, NULL),
                        ('t2', 'V94 Test', 'Catsuit Kappa Authentic Vetra',            1, 'Traje',    'indumentaria', '',         true, NULL, NULL),
                        ('t3', 'V94 Test', 'Conjunto Puma Teamrise Tracksuit',         1, 'Conjunto', 'indumentaria', 'deportivo', true, NULL, NULL),
                        ('l1', 'V94 Test', 'CAMPERA TRACKSUIT NEGRA',                  1, 'Traje',    'indumentaria', '',         true, NULL, NULL),
                        ('l2', 'V94 Test', 'PANTALÓN TRACKSUIT MARRÓN',                1, 'Traje',    'indumentaria', '',         true, NULL, NULL),
                        ('l3', 'V94 Test', 'Pantalón Reebok Vector Tracksuit',         1, 'Traje',    'indumentaria', '',         true, NULL, NULL),
                        ('s1', 'V94 Test', 'Traje Sastre Hombre',                      1, 'Traje',    'indumentaria', '',         true, NULL, NULL),
                        ('s2', 'V94 Test', 'Conjunto Deportivo Nike',                  1, 'Conjunto', 'indumentaria', 'deportivo', true, NULL, NULL),
                        ('k1', 'V94 Test', 'Tracksuit Bloqueado',                      1, 'Traje',    'indumentaria', '',         true, 'local', now())
                    """);
            aplicar(st);

            assertThat(fila(st, "t1")).isEqualTo("Traje Deportivo/indumentaria");
            assertThat(fila(st, "t2")).isEqualTo("Traje Deportivo/indumentaria");
            assertThat(fila(st, "t3")).isEqualTo("Traje Deportivo/indumentaria");
            assertThat(fila(st, "l1")).isEqualTo("Campera/indumentaria");
            assertThat(fila(st, "l2")).isEqualTo("Pantalón/indumentaria");
            assertThat(fila(st, "l3")).isEqualTo("Pantalón/indumentaria");
            assertThat(fila(st, "s1")).isEqualTo("Traje/indumentaria");
            assertThat(fila(st, "s2")).isEqualTo("Conjunto/indumentaria");
            assertThat(fila(st, "k1")).isEqualTo("Traje/indumentaria");

            String antes = instantanea(st);
            aplicar(st);
            assertThat(instantanea(st)).as("idempotent").isEqualTo(antes);
        });
    }

    @Test
    @DisplayName("every Funko row is a Juguete / juguetes with no sport subcategory; other brands and locked rows stay")
    void funko() throws Exception {
        enTransaccion(st -> {
            st.execute("""
                    INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, sub_categoria, activo, bloqueado_por, bloqueado_at) VALUES
                        ('f1', 'V94 Test', 'Funko Pop! Llavero The Flash',                 1, 'Llavero',              'juguetes',     '',        true, NULL, NULL),
                        ('f2', 'V94 Test', 'Funko Pop! One Piece Luffy Mini-Backpack',     1, 'Malla',                'indumentaria', 'entera',  true, NULL, NULL),
                        ('f3', 'V94 Test', 'Funko Pop! Alien 3 - Ripley & The Runner',     1, 'Figura Coleccionable', 'juguetes',     'running', true, NULL, NULL),
                        ('f4', 'V94 Test', 'Mochila Funko POP Villanos',                   1, 'Mochila',              'indumentaria', '',        true, NULL, NULL),
                        ('f5', 'V94 Test', 'Peluche Funko Pop Grogu',                      1, 'Peluche',              'juguetes',     '',        true, NULL, NULL),
                        ('f6', 'V94 Test', 'Mochila Funko/Sanrio: Hello Kitty Mini-Backpack', 1, 'Mochila',           'indumentaria', '',        true, NULL, NULL),
                        ('o1', 'V94 Test', 'Figura Coleccionable Bandai',                  1, 'Figura Coleccionable', 'juguetes',     '',        true, NULL, NULL),
                        ('k1', 'V94 Test', 'Funko Bloqueado',                              1, 'Llavero',              'juguetes',     '',        true, 'local', now())
                    """);
            aplicar(st);

            for (String u : new String[]{"f1", "f2", "f3", "f4", "f5", "f6"}) {
                assertThat(uno(st, "SELECT categoria || '/' || rubro || '/' || coalesce(sub_categoria, '') FROM productos WHERE url = '" + u + "'"))
                        .isEqualTo("Juguete/juguetes/");
            }
            assertThat(fila(st, "o1")).isEqualTo("Figura Coleccionable/juguetes");
            assertThat(fila(st, "k1")).isEqualTo("Llavero/juguetes");
        });
    }

    @Test
    @DisplayName("LEGO toys get sub_categoria 'lego'; Legolas, videogames and books do not")
    void lego() throws Exception {
        enTransaccion(st -> {
            st.execute("""
                    INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, sub_categoria, activo, bloqueado_por, bloqueado_at) VALUES
                        ('l1', 'V94 Test', 'LEGO Classic Caja Creativa 10696',  1, 'Juguete',   'juguetes', '',        true, NULL, NULL),
                        ('l2', 'V94 Test', 'Lego Futbol Equipo Barcelona',      1, 'Juguete',   'juguetes', 'fútbol',  true, NULL, NULL),
                        ('l3', 'V94 Test', 'Lego City Runner',                  1, 'Juguete',   'juguetes', 'running', true, NULL, NULL),
                        ('l4', 'V94 Test', 'LEGO: Cuentos Fantasticos Sobre Dragones', 1, 'Juguete', 'juguetes', '',       true, NULL, NULL),
                        ('n1', 'V94 Test', 'Legolas Figura Hobbit',             1, 'Juguete',   'juguetes', '',        true, NULL, NULL),
                        ('n2', 'V94 Test', 'LEGO Star Wars Skywalker Saga PS5', 1, 'Videojuego','tecnologia', '',      true, NULL, NULL),
                        ('n3', 'V94 Test', 'LEGO Harry Potter Guia Visual',     1, 'Libro',     'libros',   '',        true, NULL, NULL),
                        ('k1', 'V94 Test', 'LEGO Bloqueado',                    1, 'Juguete',   'juguetes', '',        true, 'local', now())
                    """);
            aplicar(st);

            for (String u : new String[]{"l1", "l2", "l3", "l4"}) {
                assertThat(uno(st, "SELECT sub_categoria FROM productos WHERE url = '" + u + "'")).isEqualTo("lego");
            }
            for (String u : new String[]{"n1", "n2", "n3", "k1"}) {
                assertThat(uno(st, "SELECT coalesce(sub_categoria, '') FROM productos WHERE url = '" + u + "'")).isEmpty();
            }
        });
    }

    private interface Cuerpo {
        void correr(Statement st) throws Exception;
    }

    private void enTransaccion(Cuerpo cuerpo) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET CONSTRAINTS ALL IMMEDIATE");
                st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                        + "VALUES ('V94 Test', 'v94test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static String fila(Statement st, String url) throws Exception {
        return uno(st, "SELECT categoria || '/' || coalesce(rubro,'') FROM productos WHERE url = '" + url + "'");
    }

    private static String instantanea(Statement st) throws Exception {
        return uno(st, "SELECT string_agg(url || categoria || coalesce(rubro,'') || coalesce(sub_categoria,''), ',' ORDER BY url) FROM productos");
    }

    private static void aplicar(Statement st) throws Exception {
        try (InputStream in = V94VinchaBandanaTrajeDeportivoTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            st.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String uno(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
