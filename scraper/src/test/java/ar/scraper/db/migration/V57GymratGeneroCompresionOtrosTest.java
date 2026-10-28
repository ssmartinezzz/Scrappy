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

/** Replays V57's own SQL over seeded rows. Names are real rows from the dev database (2026-10-28). */
@DisplayName("V57 — women signals, compression tees and gym tops out of Otros, nothing else moves")
class V57GymratGeneroCompresionOtrosTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V57__gymrat_genero_compresion_otros.sql";

    @Test
    @DisplayName("Otros rows with a garment token move to their category, sub_categoria reset")
    void muevenDeOtros() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());

            assertThat(fila(st, "tank")).isEqualTo("Musculosa||");
            assertThat(fila(st, "stringer")).isEqualTo("Musculosa||");
            assertThat(fila(st, "musculos")).isEqualTo("Musculosa||");
            assertThat(fila(st, "boxy")).isEqualTo("Remera||");
            assertThat(fila(st, "longsleeve")).isEqualTo("Remera||");
            assertThat(fila(st, "croptop")).isEqualTo("Corpino||mujer");
            assertThat(fila(st, "bras")).isEqualTo("Corpino||mujer");
            assertThat(fila(st, "bandana")).isEqualTo("Gorra||");
            assertThat(fila(st, "compression-tank")).isEqualTo("Musculosa|compresión|");
        });
    }

    @Test
    @DisplayName("Non-apparel Otros, locked rows and unrelated categories are untouched")
    void noTocaLoQueNoEsSuyo() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());

            assertThat(fila(st, "gift")).isEqualTo("Otros||");
            assertThat(fila(st, "llavero")).isEqualTo("Otros||");
            assertThat(fila(st, "tank-bloqueado")).isEqualTo("Otros||");
            assertThat(fila(st, "think-tank")).isEqualTo("Mochila||");
            assertThat(fila(st, "ecotank")).isEqualTo("Impresión||");
            assertThat(fila(st, "jean-slim")).isEqualTo("Jean||");
        });
    }

    @Test
    @DisplayName("Blank genero rows with a women signal become mujer; the rest keep what they had")
    void generoPorSenal() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());

            assertThat(fila(st, "wmn")).isEqualTo("Short||mujer");
            assertThat(fila(st, "girl-null")).isEqualTo("Musculosa||mujer");
            assertThat(fila(st, "wmn-hombre")).isEqualTo("Short||hombre");
            assertThat(fila(st, "wmn-bloqueado")).isEqualTo("Short||");
            assertThat(fila(st, "sin-senal")).isEqualTo("Short||");
            assertThat(fila(st, "brasilia")).isEqualTo("Mochila||");
        });
    }

    @Test
    @DisplayName("Compression Remera and Musculosa get the compresión sub_categoria")
    void compresion() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());

            assertThat(fila(st, "remera-compression")).isEqualTo("Remera|compresión|");
            assertThat(fila(st, "remera-compresion-acento")).isEqualTo("Remera|compresión|");
            assertThat(fila(st, "remera-plana")).isEqualTo("Remera|deportiva|");
            assertThat(fila(st, "calza-compression")).isEqualTo("Calza||mujer");
            assertThat(fila(st, "remera-compression-bloqueada")).isEqualTo("Remera|deportiva|");
        });
    }

    @Test
    @DisplayName("A second run touches no row")
    void esIdempotente() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());
            st.execute("CREATE TEMP TABLE antes AS SELECT url, categoria, sub_categoria, genero FROM productos");

            st.execute(migracion());

            try (ResultSet rs = st.executeQuery("""
                    SELECT count(*) FROM productos p JOIN antes a USING (url)
                     WHERE (p.categoria, p.sub_categoria, p.genero)
                           IS DISTINCT FROM (a.categoria, a.sub_categoria, a.genero)""")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        });
    }

    @Test
    @DisplayName("The documented rollback block runs")
    void rollbackDocumentadoCorre() throws Exception {
        enTransaccion(st -> st.execute(DocumentedRollback.sqlFor("V57")));
    }

    private interface Cuerpo {
        void correr(Statement st) throws Exception;
    }

    private void enTransaccion(Cuerpo cuerpo) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                sembrar(st);
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static void sembrar(Statement st) throws Exception {
        st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                + "VALUES ('V57 Test', 'v57test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
        st.execute("""
                INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, genero,
                                       bloqueado_por, bloqueado_at) VALUES
                    ('tank',             'V57 Test', 'Morley Tank',                          1, 'Otros',  'x',        '',      NULL, NULL),
                    ('stringer',         'V57 Test', 'Back Day Stringer',                    1, 'Otros',  '',         '',      NULL, NULL),
                    ('musculos',         'V57 Test', 'MUSCULOS OVERSIZE SIGNAL CHOCOLATE',   1, 'Otros',  '',         '',      NULL, NULL),
                    ('boxy',             'V57 Test', 'Boxy "Club"',                          1, 'Otros',  '',         '',      NULL, NULL),
                    ('longsleeve',       'V57 Test', 'Mkf Long Sleeve',                      1, 'Otros',  '',         '',      NULL, NULL),
                    ('croptop',          'V57 Test', 'CROPTOP 4REAL NEGRO',                  1, 'Otros',  '',         '',      NULL, NULL),
                    ('bras',             'V57 Test', 'Bras Cruzado',                         1, 'Otros',  '',         NULL,    NULL, NULL),
                    ('bandana',          'V57 Test', 'Dealer Bandana',                       1, 'Otros',  '',         '',      NULL, NULL),
                    ('compression-tank', 'V57 Test', 'Compression Tank Turtleneck',          1, 'Otros',  '',         '',      NULL, NULL),
                    ('gift',             'V57 Test', 'Gift Card MKF $50000',                 1, 'Otros',  '',         '',      NULL, NULL),
                    ('llavero',          'V57 Test', 'Monky\\Llavero',                       1, 'Otros',  '',         '',      NULL, NULL),
                    ('tank-bloqueado',   'V57 Test', 'Air Tank',                             1, 'Otros',  '',         '',      'santi', now()),
                    ('think-tank',       'V57 Test', 'Think Tank Mochila Photo',             1, 'Mochila','',         '',      NULL, NULL),
                    ('ecotank',          'V57 Test', 'Impresora Epson EcoTank L3250',        1, 'Impresión','',       '',      NULL, NULL),
                    ('jean-slim',        'V57 Test', 'Jean Slim Fit Azul',                   1, 'Jean',   '',         '',      NULL, NULL),
                    ('wmn',              'V57 Test', 'Short Wmn Training',                   1, 'Short',  '',         '',      NULL, NULL),
                    ('girl-null',        'V57 Test', 'Girl Tank Negra',                      1, 'Musculosa','',       NULL,    NULL, NULL),
                    ('wmn-hombre',       'V57 Test', 'Short Wmn Training',                   1, 'Short',  '',         'hombre',NULL, NULL),
                    ('wmn-bloqueado',    'V57 Test', 'Short Wmn Bloqueado',                  1, 'Short',  '',         '',      'santi', now()),
                    ('sin-senal',        'V57 Test', 'Short Training Negro',                 1, 'Short',  '',         '',      NULL, NULL),
                    ('brasilia',         'V57 Test', 'Brasilia Duffel',                      1, 'Mochila','',         '',      NULL, NULL),
                    ('remera-compression','V57 Test','Remera Compression Training',         1, 'Remera', 'deportiva','',      NULL, NULL),
                    ('remera-compresion-acento','V57 Test','Remera de Compresión Gym',       1, 'Remera', 'deportiva','',      NULL, NULL),
                    ('remera-plana',     'V57 Test', 'Remera Training Dri-Fit',              1, 'Remera', 'deportiva','',      NULL, NULL),
                    ('calza-compression','V57 Test', 'Calza Compression',                    1, 'Calza',  '',         'mujer', NULL, NULL),
                    ('remera-compression-bloqueada','V57 Test','Remera Compression Gym',    1, 'Remera', 'deportiva','',      'santi', now())
                """);
    }

    private static String migracion() throws Exception {
        try (InputStream in = V57GymratGeneroCompresionOtrosTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String fila(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT categoria, sub_categoria, genero FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("row %s", url).isTrue();
            return rs.getString(1) + "|" + rs.getString(2) + "|" + orEmpty(rs.getString(3));
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
