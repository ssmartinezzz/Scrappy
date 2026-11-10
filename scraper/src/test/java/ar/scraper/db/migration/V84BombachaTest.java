package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.CategoryClassifier;
import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Replays V84's own SQL over seeded rows (names modelled on the classifier's Bombacha cases, 2026-11-10). */
@DisplayName("V84 — Bombacha category row, backfill of Calzoncillos/Otros rows and the women's gender")
class V84BombachaTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V84__categoria_bombacha.sql";

    @Test
    @DisplayName("the migration seeds the Bombacha categoria row (the FK target)")
    void seedsTheCategoriaRow() throws Exception {
        enTransaccion(st -> {
            assertThat(unaColumna(st, "SELECT count(*) FROM categoria WHERE nombre = 'Bombacha'")).isEqualTo("1");
            // The documented rollback removes it; the replay must put it back (idempotent insert).
            st.execute(DocumentedRollback.sqlFor("V84"));
            assertThat(unaColumna(st, "SELECT count(*) FROM categoria WHERE nombre = 'Bombacha'")).isEqualTo("0");
            aplicar(st);
            assertThat(unaColumna(st, "SELECT count(*) FROM categoria WHERE nombre = 'Bombacha'")).isEqualTo("1");
        });
    }

    @Test
    @DisplayName("tangas filed as Calzoncillos or Otros move; veto words, other categories, other rubros and locked rows do not")
    void realinea() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);

            assertThat(categoria(st, "tanga-calz")).isEqualTo("Bombacha");
            assertThat(categoria(st, "panty-otros")).isEqualTo("Bombacha");
            assertThat(categoria(st, "colaless-sin-rubro")).as("NULL rubro counts as indumentaria").isEqualTo("Bombacha");
            assertThat(categoria(st, "tanga-acento")).as("accent-stripped").isEqualTo("Bombacha");
            assertThat(categoria(st, "tanga-inactiva")).isEqualTo("Bombacha");
            assertThat(categoria(st, "culotte-pantalon")).as("veto: pantalon").isEqualTo("Otros");
            assertThat(categoria(st, "bombacha-campo")).as("veto: campo").isEqualTo("Otros");
            assertThat(categoria(st, "bombacha-bikini")).as("veto: bikini").isEqualTo("Calzoncillos");
            assertThat(categoria(st, "boxer")).isEqualTo("Calzoncillos");
            assertThat(categoria(st, "tanga-remera")).as("only Calzoncillos/Otros are realigned").isEqualTo("Remera");
            assertThat(categoria(st, "tanga-tecnologia")).as("only clothing").isEqualTo("Otros");
            assertThat(categoria(st, "tanga-bloqueada")).as("locked by hand").isEqualTo("Calzoncillos");
        });
    }

    @Test
    @DisplayName("gender: moved rows become mujer, except kids' rows, an explicit men signal and locked rows")
    void generoDeLasMovidas() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);

            assertThat(genero(st, "tanga-calz")).isEqualTo("mujer");
            assertThat(genero(st, "panty-otros")).as("NULL genero").isEqualTo("mujer");
            assertThat(genero(st, "tanga-infantil")).isEqualTo("infantil");
            assertThat(genero(st, "tanga-hombre")).as("explicit men signal in the name").isEqualTo("hombre");
            assertThat(genero(st, "tanga-bloqueada")).isEqualTo("hombre");
            assertThat(genero(st, "boxer")).as("not a Bombacha").isEqualTo("hombre");
        });
    }

    @Test
    @DisplayName("A second run touches no row")
    void esIdempotente() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);
            String antes = snapshot(st);

            aplicar(st);

            assertThat(snapshot(st)).isEqualTo(antes);
        });
    }

    @Test
    @DisplayName("SQL and CategoryClassifier agree on what is a Bombacha")
    void paridadConElClasificador() throws Exception {
        CategoryClassifier clasificador = new CategoryClassifier();
        List<String> nombres = List.of(
                "Bombacha Algodón Mujer", "Bombachas Lisas Mujer", "Panty Algodón Negro", "Ropa Interior Panty",
                "Panties de Encaje", "Culotte Sin Costura Mujer", "Colaless Microfibra Negro", "Cola Less Blanca",
                "Vedetina Lisa", "Tanga Encaje Negra", "TANGA CLASICA", "Tánga Brasilera Dama", "Tanga, Encaje Negro",
                "Bombacha Bikini Rayada", "Tanga de Baño Mujer", "Bombacha Malla Entera", "Tanga Playa Estampada",
                "Bombacha Swim Bottom", "Pantalón Culotte Lino", "Culotte Jean Tiro Alto", "Bombacha Bermuda Mujer",
                "Culotte Short Mujer", "Bombacha de Campo Lisa", "Bombacha Gaucho Hombre", "Bombacha Gabardina Beige",
                "Bombacha Rural Reforzada", "Boxer Algodón Hombre Negro", "Slip Clásico Hombre");
        enTransaccion(st -> {
            int i = 0;
            for (String n : nombres) {
                st.execute("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) VALUES ('p" + i++
                        + "', 'V84 Test', '" + n + "', 1, 'Calzoncillos', 'indumentaria', true)");
            }
            aplicar(st);

            try (ResultSet rs = st.executeQuery("SELECT nombre, categoria FROM productos ORDER BY url")) {
                int filas = 0;
                while (rs.next()) {
                    boolean bombacha = "Bombacha".equals(clasificador.normalizarCategoria("", rs.getString(1)));
                    assertThat("Bombacha".equals(rs.getString(2))).as(rs.getString(1)).isEqualTo(bombacha);
                    filas++;
                }
                assertThat(filas).isEqualTo(nombres.size());
            }
        });
    }

    @Test
    @DisplayName("The documented rollback sends the rows back to Calzoncillos and removes the categoria row")
    void rollbackDocumentadoCorre() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);
            assertThat(categoria(st, "tanga-calz")).isEqualTo("Bombacha");

            st.execute(DocumentedRollback.sqlFor("V84"));

            assertThat(categoria(st, "tanga-calz")).isEqualTo("Calzoncillos");
            assertThat(unaColumna(st, "SELECT count(*) FROM categoria WHERE nombre = 'Bombacha'")).isEqualTo("0");
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
                        + "VALUES ('V84 Test', 'v84test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static void sembrar(Statement st) throws Exception {
        st.execute("""
                INSERT INTO productos (url, sitio, nombre, precio, categoria, genero, rubro, activo, bloqueado_por, bloqueado_at) VALUES
                    ('tanga-calz',         'V84 Test', 'Tanga Encaje Negra',          1, 'Calzoncillos', 'hombre',   'indumentaria', true,  NULL,    NULL),
                    ('panty-otros',        'V84 Test', 'Panty Algodón Negro',         1, 'Otros',        NULL,       'indumentaria', true,  NULL,    NULL),
                    ('colaless-sin-rubro', 'V84 Test', 'Colaless Microfibra',         1, 'Otros',        'mujer',    NULL,           true,  NULL,    NULL),
                    ('tanga-acento',       'V84 Test', 'Tánga Brasilera Dama',        1, 'Calzoncillos', 'hombre',   'indumentaria', true,  NULL,    NULL),
                    ('tanga-inactiva',     'V84 Test', 'Tanga Clásica Blanca',        1, 'Calzoncillos', 'hombre',   'indumentaria', false, NULL,    NULL),
                    ('tanga-infantil',     'V84 Test', 'Tanga Niña Algodón',          1, 'Calzoncillos', 'infantil', 'indumentaria', true,  NULL,    NULL),
                    ('tanga-hombre',       'V84 Test', 'Tanga Slip para Hombre',      1, 'Calzoncillos', 'hombre',   'indumentaria', true,  NULL,    NULL),
                    ('culotte-pantalon',   'V84 Test', 'Pantalón Culotte Lino',       1, 'Otros',        'mujer',    'indumentaria', true,  NULL,    NULL),
                    ('bombacha-campo',     'V84 Test', 'Bombacha de Campo Lisa',      1, 'Otros',        'hombre',   'indumentaria', true,  NULL,    NULL),
                    ('bombacha-bikini',    'V84 Test', 'Bombacha Bikini Rayada',      1, 'Calzoncillos', 'mujer',    'indumentaria', true,  NULL,    NULL),
                    ('boxer',              'V84 Test', 'Boxer Algodón Hombre Negro',  1, 'Calzoncillos', 'hombre',   'indumentaria', true,  NULL,    NULL),
                    ('tanga-remera',       'V84 Test', 'Remera Tanga Print',          1, 'Remera',       'mujer',    'indumentaria', true,  NULL,    NULL),
                    ('tanga-tecnologia',   'V84 Test', 'Tanga Cable Organizer',       1, 'Otros',        NULL,       'tecnologia',   true,  NULL,    NULL),
                    ('tanga-bloqueada',    'V84 Test', 'Tanga Bloqueada',             1, 'Calzoncillos', 'hombre',   'indumentaria', true,  'local', now())
                """);
    }

    private static void aplicar(Statement st) throws Exception {
        st.execute(migracion());
    }

    private static String migracion() throws Exception {
        try (InputStream in = V84BombachaTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String unaColumna(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static String categoria(Statement st, String url) throws Exception {
        return unaColumna(st, "SELECT categoria FROM productos WHERE url = '" + url + "'");
    }

    private static String genero(Statement st, String url) throws Exception {
        return unaColumna(st, "SELECT genero FROM productos WHERE url = '" + url + "'");
    }

    private static String snapshot(Statement st) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ResultSet rs = st.executeQuery("SELECT url, categoria, genero FROM productos ORDER BY url")) {
            while (rs.next()) sb.append(rs.getString(1)).append('=').append(rs.getString(2)).append('/')
                    .append(rs.getString(3)).append(';');
        }
        return sb.toString();
    }
}
