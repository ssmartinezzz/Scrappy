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

/** Replays V80's own SQL over seeded rows. Names are real rows from the dev database (2026-11-10). */
@DisplayName("V80 — every El Mundo del Juguete row is infantil, nothing else moves")
class V80ElMundoDelJugueteInfantilTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V80__genero_infantil_elmundodeljuguete.sql";

    @Test
    @DisplayName("Blank, null, mujer and hombre rows of the toy store become infantil")
    void muevenAInfantil() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());

            assertThat(genero(st, "emj-blank")).isEqualTo("infantil");
            assertThat(genero(st, "emj-null")).isEqualTo("infantil");
            assertThat(genero(st, "emj-mujer")).isEqualTo("infantil");
            assertThat(genero(st, "emj-hombre")).isEqualTo("infantil");
        });
    }

    @Test
    @DisplayName("Other sites and locked rows are untouched")
    void noTocaLoQueNoEsSuyo() throws Exception {
        enTransaccion(st -> {
            st.execute(migracion());

            assertThat(genero(st, "otro-blank")).isEmpty();
            assertThat(genero(st, "otro-mujer")).isEqualTo("mujer");
            assertThat(genero(st, "emj-bloqueado")).isEqualTo("mujer");
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
        enTransaccion(st -> st.execute(DocumentedRollback.sqlFor("V80")));
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
                + "VALUES ('V80 Test', 'v80test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
        st.execute("""
                INSERT INTO productos (url, sitio, nombre, precio, categoria, genero, bloqueado_por, bloqueado_at) VALUES
                    ('emj-blank',     'Elmundodeljuguete', 'Mochila Espalda 12 Pulgadas Tractores Tractor', 1, 'Mochila',  '',       NULL,    NULL),
                    ('emj-null',      'Elmundodeljuguete', 'Pantufla Stitch',                               1, 'Pantufla', NULL,     NULL,    NULL),
                    ('emj-mujer',     'Elmundodeljuguete', 'Ditoys Princess Doll Vestido Blanco Rubia',     1, 'Vestido',  'mujer',  NULL,    NULL),
                    ('emj-hombre',    'Elmundodeljuguete', 'Muñeco The Avengers Titan Hero Iron Man',       1, 'Juguete',  'hombre', NULL,    NULL),
                    ('emj-bloqueado', 'Elmundodeljuguete', 'Vestido Bloqueado',                             1, 'Vestido',  'mujer',  'santi', now()),
                    ('otro-blank',    'V80 Test',          'Mochila Urbana 20 Lts',                         1, 'Mochila',  '',       NULL,    NULL),
                    ('otro-mujer',    'V80 Test',          'Vestido Lino',                                  1, 'Vestido',  'mujer',  NULL,    NULL)
                """);
    }

    private static String migracion() throws Exception {
        try (InputStream in = V80ElMundoDelJugueteInfantilTest.class.getResourceAsStream(MIGRATION)) {
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
