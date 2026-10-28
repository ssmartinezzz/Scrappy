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

/**
 * Flyway already ran V56 against an empty catalog, so the test replays the migration's own SQL
 * over seeded rows. Names are real rows from the dev database (2026-10-27).
 */
@DisplayName("V56 — clothing-led names leave hardware categories, and nothing else moves")
class V56PrendaLiderFueraDeTechTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V56__prenda_lider_fuera_de_tech.sql";

    @Test
    @DisplayName("Each clothing leader lands in its category, with sub_categoria reset")
    void reclasificaPorLider() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());

            assertThat(fila(st, "zapa-gtx")).isEqualTo("Zapatilla||false");
            assertThat(fila(st, "zapa-hiker")).isEqualTo("Borcego||false");
            assertThat(fila(st, "remera-arc")).isEqualTo("Remera||true");
            assertThat(fila(st, "mochila-nb")).isEqualTo("Mochila||false");
            assertThat(fila(st, "sweater-mother")).isEqualTo("Sweater||false");
            assertThat(fila(st, "chaqueta-intel")).isEqualTo("Campera||false");
            assertThat(fila(st, "pelota-rx")).isEqualTo("Pelota||true");
            assertThat(fila(st, "lonchera-cooler")).isEqualTo("Bolso||true");
            assertThat(fila(st, "lonchera-otros")).isEqualTo("Bolso||false");
            assertThat(fila(st, "vitamina-tablets")).isEqualTo("Vitaminas||false");
            assertThat(fila(st, "biotin-tablets")).isEqualTo("Otros||false");
        });
    }

    @Test
    @DisplayName("Locked rows, real hardware and out-of-scope drift are untouched")
    void noTocaLoQueNoEsSuyo() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());

            assertThat(fila(st, "zapa-bloqueada")).isEqualTo("GPU|gaming|true");
            assertThat(fila(st, "gpu-real")).isEqualTo("GPU|gaming|false");
            assertThat(fila(st, "tablet-real")).isEqualTo("Tablet||false");
            assertThat(fila(st, "zapa-otros")).isEqualTo("Otros|running|false");
        });
    }

    @Test
    @DisplayName("A second run touches no row")
    void esIdempotente() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());
            st.execute("CREATE TEMP TABLE antes AS SELECT url, categoria, sub_categoria, gymrat FROM productos");

            st.execute(migracion());

            try (ResultSet rs = st.executeQuery("""
                    SELECT count(*) FROM productos p JOIN antes a USING (url)
                     WHERE (p.categoria, p.sub_categoria, p.gymrat)
                           IS DISTINCT FROM (a.categoria, a.sub_categoria, a.gymrat)""")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        });
    }

    @Test
    @DisplayName("The documented rollback block runs")
    void rollbackDocumentadoCorre() throws Exception {
        enTransaccion(st -> st.execute(DocumentedRollback.sqlFor("V56")));
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
                + "VALUES ('V56 Test', 'v56test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
        st.execute("""
                INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, gymrat,
                                       bloqueado_por, bloqueado_at) VALUES
                    ('zapa-gtx',         'V56 Test', 'Zapatillas Salomon X Ultra 360 Mid Gtx',            1, 'GPU',         '',        true,  NULL, NULL),
                    ('zapa-hiker',       'V56 Test', 'Zapatillas adidas Terrex Free Hiker 2.0 Gtx',       1, 'GPU',         '',        true,  NULL, NULL),
                    ('remera-arc',       'V56 Test', 'Remera Under Armour Curry Arc',                     1, 'GPU',         '',        true,  NULL, NULL),
                    ('mochila-nb',       'V56 Test', 'Mochila Gamer Redragon Traveller GB-96 Notebook',   1, 'Notebook',    'gamer',   false, NULL, NULL),
                    ('sweater-mother',   'V56 Test', 'Sweater Canguro Mother Negro',                      1, 'Motherboard', '',        false, NULL, NULL),
                    ('chaqueta-intel',   'V56 Test', 'Chaquetas de nieve Dc Shoes Unisex Intel 30k',      1, 'CPU',         '',        false, NULL, NULL),
                    ('pelota-rx',        'V56 Test', 'Pelotas De Padel adidas Speed Rx Tubo X 3',         1, 'GPU',         'pádel',   true,  NULL, NULL),
                    ('lonchera-cooler',  'V56 Test', 'Lonchera adidas Termico Cooler S',                  1, 'Cooler',      '',        true,  NULL, NULL),
                    ('lonchera-otros',   'V56 Test', 'Lonchera Roxy Hour',                                1, 'Otros',       '',        false, NULL, NULL),
                    ('vitamina-tablets', 'V56 Test', 'MYPROTEIN Daily Vitamins Multi Vitamin 60 Tablets', 1, 'Tablet',      '',        false, NULL, NULL),
                    ('biotin-tablets',   'V56 Test', 'NATURE''S BOUNTY Biotin 1000mcg 100 Tablets',       1, 'Tablet',      '',        false, NULL, NULL),
                    ('zapa-bloqueada',   'V56 Test', 'Zapatillas Salomon Ultra Flow Gtx',                 1, 'GPU',         'gaming',  true,  'santi', now()),
                    ('gpu-real',         'V56 Test', 'Placa de Video ASUS Dual GeForce RTX 4060 8GB',     1, 'GPU',         'gaming',  false, NULL, NULL),
                    ('tablet-real',      'V56 Test', 'Tablet Samsung Galaxy Tab A9 64GB',                 1, 'Tablet',      '',        false, NULL, NULL),
                    ('zapa-otros',       'V56 Test', 'Zapatillas Running Asics Gel Kayano 32',            1, 'Otros',       'running', false, NULL, NULL)
                """);
    }

    private static String migracion() throws Exception {
        try (InputStream in = V56PrendaLiderFueraDeTechTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String fila(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT categoria, sub_categoria, gymrat FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("row %s", url).isTrue();
            return rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getBoolean(3);
        }
    }
}
