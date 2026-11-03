package ar.scraper.db.migration;

import ar.scraper.aggregator.normalize.GenderResolver;
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

/** Replays V60's own SQL over seeded rows, like {@code V59DeporteTest}. */
@DisplayName("V60 — kids size suffixes backfill genero = 'infantil'")
class V60InfantilSufijosTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V60__infantil_sufijos.sql";
    private static final List<String> TOKENS = List.of("jr", "junior", "kid", "kids", "youth", "ps", "gs", "td", "toddler");

    @Test
    @DisplayName("Tags suffix rows, leaves Boca Juniors, locked rows and non-clothing untouched")
    void backfill() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                sembrar(st);

                aplicar(st);

                assertThat(genero(st, "https://t/jr")).isEqualTo("infantil");
                assertThat(genero(st, "https://t/punct")).isEqualTo("infantil");
                assertThat(genero(st, "https://t/inactiva")).isEqualTo("infantil");
                assertThat(genero(st, "https://t/sin-genero")).isEqualTo("infantil");
                assertThat(genero(st, "https://t/boca")).isEqualTo("hombre");
                assertThat(genero(st, "https://t/argentinos")).isEqualTo("hombre");
                assertThat(genero(st, "https://t/bloqueada")).isEqualTo("hombre");
                assertThat(genero(st, "https://t/tech")).isEqualTo("");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("A second run changes nothing")
    void idempotent() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                sembrar(st);
                aplicar(st);
                String antes = snapshot(st);

                aplicar(st);

                assertThat(snapshot(st)).isEqualTo(antes);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("SQL and GenderResolver agree on every token, and on the lookalikes")
    void parityWithJava() throws Exception {
        GenderResolver resolver = new GenderResolver();
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                        + "VALUES ('V60 Test', 'v60test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
                int i = 0;
                for (String t : TOKENS) {
                    insertar(st, i++, "Zapatilla Air Max " + t.toUpperCase());
                    insertar(st, i++, "Zapatilla Air Max " + t + "s");
                    insertar(st, i++, "Zapatilla Air Max " + t + "ex");
                }
                insertar(st, i++, "Camiseta adidas Titular Boca Juniors 26/27");
                aplicar(st);

                try (ResultSet rs = st.executeQuery("SELECT nombre, genero FROM productos ORDER BY url")) {
                    int rows = 0;
                    while (rs.next()) {
                        String esperado = resolver.resolver("", rs.getString(1), "Zapatilla").equals("infantil")
                                ? "infantil" : "";
                        assertThat(rs.getString(2)).as(rs.getString(1)).isEqualTo(esperado);
                        rows++;
                    }
                    assertThat(rows).isEqualTo(i);
                }
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("The documented rollback block runs")
    void rollbackDocumentadoCorre() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(DocumentedRollback.sqlFor("V60"));
        }
    }

    private static void insertar(Statement st, int i, String nombre) throws Exception {
        st.execute("INSERT INTO productos (url, sitio, nombre, precio, rubro, genero, activo) VALUES "
                + "('https://p/" + String.format("%03d", i) + "', 'V60 Test', '" + nombre + "', 1000, 'indumentaria', '', true)");
    }

    private static void sembrar(Statement st) throws Exception {
        st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                + "VALUES ('V60 Test', 'v60test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
        st.execute("""
                INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, genero, activo, bloqueado_por, bloqueado_at) VALUES
                    ('https://t/jr',         'V60 Test', 'Zapatilla Nike Air Max 90 Jr',                  1000, 'Zapatilla', 'indumentaria', 'hombre', true,  NULL,    NULL),
                    ('https://t/punct',      'V60 Test', 'Antiparras adidas Ripstream Starter Jr.',       1000, 'Otros',     'indumentaria', 'unisex', true,  NULL,    NULL),
                    ('https://t/inactiva',   'V60 Test', 'Campera Youth Negra',                           1000, 'Campera',   'indumentaria', 'mujer',  false, NULL,    NULL),
                    ('https://t/sin-genero', 'V60 Test', 'Remera Kid',                                    1000, 'Remera',    NULL,           NULL,     true,  NULL,    NULL),
                    ('https://t/boca',       'V60 Test', 'Camiseta adidas Titular Boca Juniors 26/27',    1000, 'Remera',    'indumentaria', 'hombre', true,  NULL,    NULL),
                    ('https://t/argentinos', 'V60 Test', 'Camiseta Argentinos Juniors Local',             1000, 'Remera',    'indumentaria', 'hombre', true,  NULL,    NULL),
                    ('https://t/bloqueada',  'V60 Test', 'Remera Jr Bloqueada',                           1000, 'Remera',    'indumentaria', 'hombre', true,  'local', now()),
                    ('https://t/tech',       'V60 Test', 'Joystick PS 5 Negro',                           1000, 'Joystick',  'tecnologia',   '',       true,  NULL,    NULL)
                """);
    }

    private static void aplicar(Statement st) throws Exception {
        st.execute("SET CONSTRAINTS ALL IMMEDIATE");
        st.execute(migracion());
    }

    private static String migracion() throws Exception {
        try (InputStream in = V60InfantilSufijosTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String genero(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT coalesce(genero, '<null>') FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("row %s", url).isTrue();
            return rs.getString(1);
        }
    }

    private static String snapshot(Statement st) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ResultSet rs = st.executeQuery("SELECT url, genero FROM productos ORDER BY url")) {
            while (rs.next()) sb.append(rs.getString(1)).append('=').append(rs.getString(2)).append(';');
        }
        return sb.toString();
    }
}
