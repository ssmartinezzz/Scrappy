package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.SportResolver;
import ar.scraper.aggregator.normalize.SportTaxonomy;
import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** Replays V85's own SQL over seeded rows. Names are modelled on Burton / DC rows (2026-11-10). */
@DisplayName("V85 — deporte 'snowboard': row, backfill of unsported and natacion rows, rollback")
class V85DeporteSnowboardTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V85__deporte_snowboard.sql";

    @Test
    @DisplayName("the migration seeds the snowboard deporte row (the FK target)")
    void seedsTheDeporteRow() throws Exception {
        enTransaccion(st -> {
            assertThat(unaColumna(st, "SELECT nombre FROM deporte WHERE codigo = 'snowboard'")).isEqualTo("Snowboard");
        });
    }

    @Test
    @DisplayName("snow rows with no sport or filed as natacion move; other sports, Botines, other rubros and locked rows do not")
    void realinea() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);

            assertThat(deporte(st, "tabla")).isEqualTo("snowboard");
            assertThat(deporte(st, "antiparras-anon-natacion")).as("the goggles leave natacion").isEqualTo("snowboard");
            assertThat(deporte(st, "esqui-acento")).isEqualTo("snowboard");
            assertThat(deporte(st, "snow-puntuacion")).isEqualTo("snowboard");
            assertThat(deporte(st, "sin-rubro")).as("NULL rubro counts as indumentaria").isEqualTo("snowboard");
            assertThat(deporte(st, "rubro-deporte")).isEqualTo("snowboard");
            assertThat(deporte(st, "antiparras-natacion")).as("swim goggles keep natacion").isEqualTo("natacion");
            assertThat(deporte(st, "futbol-con-snow")).as("another sport keeps it").isEqualTo("futbol");
            assertThat(deporte(st, "botin-ski")).as("Botines are excluded from the backfill").isNull();
            assertThat(deporte(st, "skinny")).as("whole words: skinny is not ski").isNull();
            assertThat(deporte(st, "snowman")).isNull();
            assertThat(deporte(st, "tecnologia")).as("only clothing and sports rubros").isNull();
            assertThat(deporte(st, "bloqueada")).as("locked by hand").isNull();
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
    @DisplayName("SQL and SportResolver agree on every snowboard keyword")
    void paridadConElResolver() throws Exception {
        SportResolver resolver = new SportResolver();
        enTransaccion(st -> {
            int i = 0;
            for (String kw : SportTaxonomy.KEYWORDS.get("snowboard")) {
                st.execute("INSERT INTO productos (url, sitio, nombre, precio, rubro, activo) VALUES ('p" + i++
                        + "', 'V85 Test', 'Prenda " + kw + " Edicion', 1000, 'indumentaria', true)");
            }
            aplicar(st);

            try (ResultSet rs = st.executeQuery("SELECT nombre, deporte FROM productos ORDER BY url")) {
                int filas = 0;
                while (rs.next()) {
                    assertThat(resolver.resolver(rs.getString(1), "Remera", "indumentaria"))
                            .as("resolver: " + rs.getString(1)).isEqualTo("snowboard");
                    assertThat(rs.getString(2)).as("SQL: " + rs.getString(1)).isEqualTo("snowboard");
                    filas++;
                }
                assertThat(filas).isEqualTo(SportTaxonomy.KEYWORDS.get("snowboard").length);
            }
        });
    }

    @Test
    @DisplayName("The documented rollback clears the sport on the moved rows and removes the deporte row")
    void rollbackDocumentadoCorre() throws Exception {
        enTransaccion(st -> {
            sembrar(st);
            aplicar(st);
            assertThat(deporte(st, "tabla")).isEqualTo("snowboard");

            st.execute(DocumentedRollback.sqlFor("V85"));

            assertThat(deporte(st, "tabla")).isNull();
            assertThat(unaColumna(st, "SELECT count(*) FROM deporte WHERE codigo = 'snowboard'")).isEqualTo("0");
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
                        + "VALUES ('V85 Test', 'v85test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static void sembrar(Statement st) throws Exception {
        st.execute("""
                INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, deporte, activo, bloqueado_por, bloqueado_at) VALUES
                    ('tabla',                    'V85 Test', 'Tabla Snowboard Custom 158',               1, 'Otros',    'indumentaria', NULL,       true, NULL,    NULL),
                    ('antiparras-anon-natacion', 'V85 Test', 'Lentes Para Antiparras Anon M5',           1, 'Otros',    'indumentaria', 'natacion', true, NULL,    NULL),
                    ('antiparras-natacion',      'V85 Test', 'Antiparras adidas Ripstream Starter Jr',   1, 'Otros',    'indumentaria', 'natacion', true, NULL,    NULL),
                    ('esqui-acento',             'V85 Test', 'Campera de Esquí Hombre',                  1, 'Campera',  'indumentaria', NULL,       true, NULL,    NULL),
                    ('snow-puntuacion',          'V85 Test', 'Campera Snow-Pro Mujer',                   1, 'Campera',  'indumentaria', NULL,       true, NULL,    NULL),
                    ('sin-rubro',                'V85 Test', 'Pantalón Ski Hombre',                      1, 'Pantalón', NULL,           NULL,       true, NULL,    NULL),
                    ('rubro-deporte',            'V85 Test', 'Casco Anon Helo',                          1, 'Otros',    'deporte',      NULL,       true, NULL,    NULL),
                    ('futbol-con-snow',          'V85 Test', 'Camiseta Boca Juniors Snow Edition',       1, 'Remera',   'indumentaria', 'futbol',   true, NULL,    NULL),
                    ('botin-ski',                'V85 Test', 'Botines Ski Edition',                      1, 'Botines',  'indumentaria', NULL,       true, NULL,    NULL),
                    ('skinny',                   'V85 Test', 'Jean Skinny Hombre',                       1, 'Jean',     'indumentaria', NULL,       true, NULL,    NULL),
                    ('snowman',                  'V85 Test', 'Remera Snowman Navidad',                   1, 'Remera',   'indumentaria', NULL,       true, NULL,    NULL),
                    ('tecnologia',               'V85 Test', 'Mouse Snow Edition',                       1, 'Mouse',    'tecnologia',   NULL,       true, NULL,    NULL),
                    ('bloqueada',                'V85 Test', 'Tabla Snowboard Bloqueada',                1, 'Otros',    'indumentaria', NULL,       true, 'local', now())
                """);
    }

    private static void aplicar(Statement st) throws Exception {
        try (InputStream in = V85DeporteSnowboardTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            st.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String unaColumna(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static String deporte(Statement st, String url) throws Exception {
        return unaColumna(st, "SELECT deporte FROM productos WHERE url = '" + url + "'");
    }

    private static String snapshot(Statement st) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ResultSet rs = st.executeQuery("SELECT url, deporte FROM productos ORDER BY url")) {
            while (rs.next()) sb.append(rs.getString(1)).append('=').append(rs.getString(2)).append(';');
        }
        return sb.toString();
    }
}
