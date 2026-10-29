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

/** Replays V58's own SQL over seeded rows. Names are real rows from the dev database (2026-10-29). */
@DisplayName("V58 — a women word in the name beats the site genero, nothing else moves")
class V58GeneroSenalNombreGanaTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V58__genero_senal_nombre_gana.sql";

    @Test
    @DisplayName("Rows with a women word and no men word become mujer whatever the site said")
    void muevenAMujer() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());

            assertThat(genero(st, "crop-hoodie")).isEqualTo("mujer");
            assertThat(genero(st, "woman-short")).isEqualTo("mujer");
            assertThat(genero(st, "pack-calza")).isEqualTo("mujer");
            assertThat(genero(st, "wmn-null")).isEqualTo("mujer");
        });
    }

    @Test
    @DisplayName("Men words, kids, locked rows, partial words and bare top are untouched")
    void noTocaLoQueNoEsSuyo() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());

            assertThat(genero(st, "calza-hombre")).isEqualTo("hombre");
            assertThat(genero(st, "men-wmn")).isEqualTo("hombre");
            assertThat(genero(st, "calza-kids")).isEmpty();
            assertThat(genero(st, "woman-bloqueado")).isEqualTo("hombre");
            assertThat(genero(st, "calzado")).isEqualTo("hombre");
            assertThat(genero(st, "top-ls")).isEqualTo("hombre");
            assertThat(genero(st, "girl-infantil")).isEqualTo("infantil");
        });
    }

    @Test
    @DisplayName("A second run touches no row")
    void esIdempotente() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());
            st.execute("CREATE TEMP TABLE antes AS SELECT url, genero FROM productos");

            st.execute(migracion());

            try (ResultSet rs = st.executeQuery("""
                    SELECT count(*) FROM productos p JOIN antes a USING (url)
                     WHERE p.genero IS DISTINCT FROM a.genero""")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
        });
    }

    @Test
    @DisplayName("The documented rollback block runs")
    void rollbackDocumentadoCorre() throws Exception {
        enTransaccion(st -> st.execute(DocumentedRollback.sqlFor("V58")));
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
                + "VALUES ('V58 Test', 'v58test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
        st.execute("""
                INSERT INTO productos (url, sitio, nombre, precio, categoria, sub_categoria, genero,
                                       bloqueado_por, bloqueado_at) VALUES
                    ('crop-hoodie',     'V58 Test', 'Crop Top Hoodie',                          1, 'Buzo',     '', 'hombre',   NULL, NULL),
                    ('woman-short',     'V58 Test', 'Woman Force Short',                        1, 'Short',    '', 'unisex',   NULL, NULL),
                    ('pack-calza',      'V58 Test', 'PACK BUZO V-ROOT YOURSELF BLACK + CALZA LARGA 4REAL BLACK + MEDIAS WHITE',
                                                                                                1, 'Conjunto', '', '',         NULL, NULL),
                    ('wmn-null',        'V58 Test', 'COMPRESSION TEE WMN LILA',                 1, 'Remera',   '', NULL,       NULL, NULL),
                    ('calza-hombre',    'V58 Test', 'Calza adidas Techfit de Hombre',           1, 'Calza',    '', 'hombre',   NULL, NULL),
                    ('men-wmn',         'V58 Test', 'Remera Men Wmn',                           1, 'Remera',   '', 'hombre',   NULL, NULL),
                    ('calza-kids',      'V58 Test', 'Calza Kids Nena',                          1, 'Calza',    '', '',         NULL, NULL),
                    ('woman-bloqueado', 'V58 Test', 'Woman Bloqueado Short',                    1, 'Short',    '', 'hombre',   'santi', now()),
                    ('calzado',         'V58 Test', 'Calzado Urbano',                           1, 'Zapatilla','', 'hombre',   NULL, NULL),
                    ('top-ls',          'V58 Test', 'Top Ls Velvet Monogram Brown',             1, 'Musculosa','', 'hombre',   NULL, NULL),
                    ('girl-infantil',   'V58 Test', 'Calza Girl',                               1, 'Calza',    '', 'infantil', NULL, NULL)
                """);
    }

    private static String migracion() throws Exception {
        try (InputStream in = V58GeneroSenalNombreGanaTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String genero(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT genero FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("row %s", url).isTrue();
            String g = rs.getString(1);
            return g == null ? "" : g;
        }
    }
}
