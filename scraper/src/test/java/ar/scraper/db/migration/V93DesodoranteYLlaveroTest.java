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

@DisplayName("V93 — Desodorante and Llavero categories, and the stale non-fragrance Perfume rows")
class V93DesodoranteYLlaveroTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V93__categorias_desodorante_y_llavero.sql";

    @Test
    @DisplayName("both categoria rows exist; the documented rollback removes them and restores Perfume / Otros")
    void categoriasYRollback() throws Exception {
        enTransaccion(st -> {
            assertThat(uno(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Desodorante', 'Llavero')")).isEqualTo("2");
            st.execute("""
                    INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) VALUES
                        ('d', 'V93 Test', 'Desodorante Racing Team 160ml', 1, 'Desodorante', 'perfumeria', true),
                        ('l', 'V93 Test', 'Llavero Insigne Full Black',    1, 'Llavero',     'indumentaria', true)
                    """);

            st.execute(DocumentedRollback.sqlFor("V93"));

            assertThat(uno(st, "SELECT count(*) FROM categoria WHERE nombre IN ('Desodorante', 'Llavero')")).isEqualTo("0");
            assertThat(uno(st, "SELECT categoria FROM productos WHERE url = 'd'")).isEqualTo("Perfume");
            assertThat(uno(st, "SELECT categoria FROM productos WHERE url = 'l'")).isEqualTo("Otros");
        });
    }

    @Test
    @DisplayName("stored deodorants move from Perfume to Desodorante/perfumeria; fragrances, locked rows and Llavero rows stay")
    void desodorantesGuardados() throws Exception {
        enTransaccion(st -> {
            st.execute("""
                    INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo, bloqueado_por, bloqueado_at) VALUES
                        ('d1', 'V93 Test', 'Desodorante Kevingston 27 160 ML', 1, 'Perfume', 'indumentaria', true, NULL, NULL),
                        ('d2', 'V93 Test', 'Desodorante Racing Team 160ml',    1, 'Perfume', 'indumentaria', true, NULL, NULL),
                        ('d3', 'V93 Test', 'Antitranspirante Roll On',         1, 'Perfume', NULL,           true, NULL, NULL),
                        ('p1', 'V93 Test', 'Perfume Mujer Eau de Parfum',      1, 'Perfume', 'perfumeria',   true, NULL, NULL),
                        ('k1', 'V93 Test', 'Desodorante Bloqueado',            1, 'Perfume', 'indumentaria', true, 'local', now()),
                        ('o1', 'V93 Test', 'Llavero Insigne Full Black',       1, 'Otros',   'indumentaria', true, NULL, NULL)
                    """);
            aplicar(st);

            for (String u : new String[]{"d1", "d2", "d3"}) {
                assertThat(uno(st, "SELECT categoria || '/' || rubro FROM productos WHERE url = '" + u + "'"))
                        .isEqualTo("Desodorante/perfumeria");
            }
            assertThat(uno(st, "SELECT categoria || '/' || rubro FROM productos WHERE url = 'p1'")).isEqualTo("Perfume/perfumeria");
            assertThat(uno(st, "SELECT categoria || '/' || rubro FROM productos WHERE url = 'k1'")).isEqualTo("Perfume/indumentaria");
            assertThat(uno(st, "SELECT categoria FROM productos WHERE url = 'o1'")).as("renormalizar owns llaveros").isEqualTo("Otros");
        });
    }

    @Test
    @DisplayName("Perfume rows with no fragrance word are realigned (rubro untouched); real fragrances, Scent and locked rows stay; idempotent")
    void perfumesViejosSinFragancia() throws Exception {
        enTransaccion(st -> {
            st.execute("""
                    INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo, bloqueado_por, bloqueado_at) VALUES
                        ('s1', 'V93 Test', 'Antiparras de Buceo Base-X Splash Azul',                      1, 'Perfume', 'juguetes',     true, NULL, NULL),
                        ('s2', 'V93 Test', 'Bracitos Splash Inflables Bestway Swim Safe 25x15',           1, 'Perfume', 'juguetes',     true, NULL, NULL),
                        ('s3', 'V93 Test', 'Splash And Play Inflable Tortuga Verde',                      1, 'Perfume', 'juguetes',     true, NULL, NULL),
                        ('s4', 'V93 Test', 'Tobogán Acuático H2OGO! Tsunami Splash Rampa Doble 4.88 m',   1, 'Perfume', 'juguetes',     true, NULL, NULL),
                        ('s5', 'V93 Test', 'Juego de Bombitas de Agua Splash Timer Funny Head Amarillo',  1, 'Perfume', 'juguetes',     true, NULL, NULL),
                        ('s6', 'V93 Test', 'Juego de Básquet Inflable Bestway Agua Splash n Hoop 61 cm',  1, 'Perfume', 'juguetes',     true, NULL, NULL),
                        ('t1', 'V93 Test', 'Teja Colonial 44 Cm 900 Un Ceramica Del Norte',               1, 'Perfume', 'construccion', true, NULL, NULL),
                        ('f1', 'V93 Test', 'Frères Scent 250 ML',                                         1, 'Perfume', 'perfumeria',   true, NULL, NULL),
                        ('f2', 'V93 Test', 'Body Splash Vainilla 200 ml',                                 1, 'Perfume', 'juguetes',     true, NULL, NULL),
                        ('f3', 'V93 Test', 'Colonia Infantil 100 ml',                                     1, 'Perfume', 'perfumeria',   true, NULL, NULL),
                        ('f4', 'V93 Test', 'Body Mist Coco',                                              1, 'Perfume', 'perfumeria',   true, NULL, NULL),
                        ('k1', 'V93 Test', 'Antiparras Bloqueadas',                                       1, 'Perfume', 'juguetes',     true, 'local', now())
                    """);
            aplicar(st);

            assertThat(fila(st, "s1")).isEqualTo("Lentes/juguetes");
            assertThat(fila(st, "s2")).isEqualTo("Inflable/juguetes");
            assertThat(fila(st, "s3")).isEqualTo("Inflable/juguetes");
            assertThat(fila(st, "s4")).isEqualTo("Inflable/juguetes");
            assertThat(fila(st, "s5")).isEqualTo("Juguete/juguetes");
            assertThat(fila(st, "s6")).isEqualTo("Inflable/juguetes");
            assertThat(fila(st, "t1")).isEqualTo("Otros/construccion");
            assertThat(fila(st, "f1")).isEqualTo("Perfume/perfumeria");
            assertThat(fila(st, "f2")).isEqualTo("Perfume/juguetes");
            assertThat(fila(st, "f3")).isEqualTo("Perfume/perfumeria");
            assertThat(fila(st, "f4")).isEqualTo("Perfume/perfumeria");
            assertThat(fila(st, "k1")).isEqualTo("Perfume/juguetes");

            String antes = uno(st, "SELECT string_agg(url || categoria || coalesce(rubro,''), ',' ORDER BY url) FROM productos");
            aplicar(st);
            assertThat(uno(st, "SELECT string_agg(url || categoria || coalesce(rubro,''), ',' ORDER BY url) FROM productos"))
                    .as("idempotent").isEqualTo(antes);
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
                        + "VALUES ('V93 Test', 'v93test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static String fila(Statement st, String url) throws Exception {
        return uno(st, "SELECT categoria || '/' || coalesce(rubro,'') FROM productos WHERE url = '" + url + "'");
    }

    private static void aplicar(Statement st) throws Exception {
        try (InputStream in = V93DesodoranteYLlaveroTest.class.getResourceAsStream(MIGRATION)) {
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
