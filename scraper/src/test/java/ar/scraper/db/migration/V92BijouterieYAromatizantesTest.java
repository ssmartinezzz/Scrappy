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

@DisplayName("V92 — Bijouterie category and car fresheners leaving Perfume")
class V92BijouterieYAromatizantesTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V92__categoria_bijouteria_y_perfume_de_auto.sql";

    @Test
    @DisplayName("the categoria row exists, and the documented rollback removes it and sends its products to Otros")
    void categoriaYRollback() throws Exception {
        enTransaccion(st -> {
            assertThat(uno(st, "SELECT count(*) FROM categoria WHERE nombre = 'Bijouterie'")).isEqualTo("1");
            st.execute("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) "
                    + "VALUES ('b', 'V92 Test', 'AROS ANNETTE', 1, 'Bijouterie', 'indumentaria', true)");

            st.execute(DocumentedRollback.sqlFor("V92"));

            assertThat(uno(st, "SELECT count(*) FROM categoria WHERE nombre = 'Bijouterie'")).isEqualTo("0");
            assertThat(uno(st, "SELECT categoria FROM productos WHERE url = 'b'")).isEqualTo("Otros");
        });
    }

    @Test
    @DisplayName("car fresheners filed as Perfume (in perfumeria or any rubro) become Limpieza de Auto / hogar; real perfume and locked rows stay")
    void aromatizantesDeAuto() throws Exception {
        enTransaccion(st -> {
            st.execute("""
                    INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo, bloqueado_por, bloqueado_at) VALUES
                        ('a1', 'V92 Test', 'Perfume para Auto Invictus 15 Cm Air Perfum', 1, 'Perfume', 'perfumeria',   true, NULL,    NULL),
                        ('a2', 'V92 Test', 'Perfume para Auto Manzana 50 Cc Silisur',     1, 'Perfume', 'construccion', true, NULL,    NULL),
                        ('a3', 'V92 Test', 'Perfumes para Auto Pack',                     1, 'Perfume', NULL,           true, NULL,    NULL),
                        ('p1', 'V92 Test', 'Perfume Mujer Eau de Parfum 100ml',           1, 'Perfume', 'perfumeria',   true, NULL,    NULL),
                        ('l1', 'V92 Test', 'Perfume para Auto Bloqueado',                 1, 'Perfume', 'perfumeria',   true, 'local', now())
                    """);
            aplicar(st);

            for (String u : new String[]{"a1", "a2", "a3"}) {
                assertThat(uno(st, "SELECT categoria || '/' || coalesce(rubro,'') FROM productos WHERE url = '" + u + "'"))
                        .isEqualTo("Limpieza de Auto/hogar");
            }
            assertThat(uno(st, "SELECT categoria || '/' || rubro FROM productos WHERE url = 'p1'")).isEqualTo("Perfume/perfumeria");
            assertThat(uno(st, "SELECT categoria || '/' || rubro FROM productos WHERE url = 'l1'")).isEqualTo("Perfume/perfumeria");

            String antes = uno(st, "SELECT string_agg(url || categoria, ',' ORDER BY url) FROM productos");
            aplicar(st);
            assertThat(uno(st, "SELECT string_agg(url || categoria, ',' ORDER BY url) FROM productos")).as("idempotent").isEqualTo(antes);

            st.execute(DocumentedRollback.sqlFor("V92"));
            assertThat(uno(st, "SELECT categoria || '/' || rubro FROM productos WHERE url = 'a1'")).isEqualTo("Perfume/perfumeria");
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
                        + "VALUES ('V92 Test', 'v92test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static void aplicar(Statement st) throws Exception {
        try (InputStream in = V92BijouterieYAromatizantesTest.class.getResourceAsStream(MIGRATION)) {
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
