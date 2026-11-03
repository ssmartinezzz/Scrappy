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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway already ran V59 against an empty catalog, so the test replays the migration's own SQL
 * over seeded rows. The file is written to be re-runnable as a whole.
 */
@DisplayName("V59 — deporte table, FK column and backfill")
class V59DeporteTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V59__deporte.sql";

    @Test
    @DisplayName("Seeds every sport of SportTaxonomy")
    void seedsTheSports() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT codigo FROM deporte ORDER BY codigo")) {
            List<String> codigos = new ArrayList<>();
            while (rs.next()) codigos.add(rs.getString(1));
            assertThat(codigos).containsExactlyInAnyOrderElementsOf(SportTaxonomy.KEYWORDS.keySet());
        }
    }

    @Test
    @DisplayName("Backfills club kits, leaves lookalikes NULL, skips locked and non-clothing rows")
    void backfill() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                sembrar(st);

                aplicar(st);

                assertThat(deporte(st, "https://t/boca")).isEqualTo("futbol");
                assertThat(deporte(st, "https://t/punct")).isEqualTo("futbol");
                assertThat(deporte(st, "https://t/botin")).isEqualTo("futbol");
                assertThat(deporte(st, "https://t/antiparras")).isEqualTo("natacion");
                assertThat(deporte(st, "https://t/sin-rubro")).isEqualTo("futbol");
                assertThat(deporte(st, "https://t/precedencia")).isEqualTo("futbol");
                assertThat(deporte(st, "https://t/kennebec")).isNull();
                assertThat(deporte(st, "https://t/arena")).isNull();
                assertThat(deporte(st, "https://t/golfista")).isNull();
                assertThat(deporte(st, "https://t/bloqueada")).isNull();
                assertThat(deporte(st, "https://t/tech")).isNull();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("Never overwrites a sport the scrape already wrote, and a second run changes nothing")
    void idempotent() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                sembrar(st);
                st.execute("UPDATE productos SET deporte = 'rugby' WHERE url = 'https://t/boca'");
                aplicar(st);
                String antes = snapshot(st);

                aplicar(st);

                assertThat(deporte(st, "https://t/boca")).isEqualTo("rugby");
                assertThat(snapshot(st)).isEqualTo(antes);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("SQL and SportResolver agree on every keyword of every sport")
    void parityWithJava() throws Exception {
        SportResolver resolver = new SportResolver();
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                        + "VALUES ('V59 Test', 'v59test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
                int i = 0;
                for (Map.Entry<String, String[]> sport : SportTaxonomy.KEYWORDS.entrySet()) {
                    for (String kw : sport.getValue()) {
                        st.execute("INSERT INTO productos (url, sitio, nombre, precio, rubro, activo) VALUES "
                                + "('https://p/" + (i++) + "', 'V59 Test', 'Prenda " + kw + " Edicion', 1000, 'indumentaria', true)");
                    }
                }
                aplicar(st);

                try (ResultSet rs = st.executeQuery("SELECT nombre, deporte FROM productos ORDER BY url")) {
                    int rows = 0;
                    while (rs.next()) {
                        String esperado = resolver.resolver(rs.getString(1), "Remera", "indumentaria");
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
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V59"));
                try (ResultSet rs = st.executeQuery(
                        "SELECT count(*) FROM information_schema.columns "
                                + "WHERE table_name = 'productos' AND column_name = 'deporte'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isZero();
                }
            } finally {
                c.rollback();
            }
        }
    }

    private static void sembrar(Statement st) throws Exception {
        st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                + "VALUES ('V59 Test', 'v59test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
        st.execute("""
                INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo, bloqueado_por, bloqueado_at) VALUES
                    ('https://t/boca',        'V59 Test', 'Camiseta adidas Titular Boca Juniors 26/27 de Hombre', 1000, 'Remera',    'indumentaria', true, NULL, NULL),
                    ('https://t/punct',       'V59 Test', 'Camiseta (River Plate), Titular',                       1000, 'Remera',    'indumentaria', true, NULL, NULL),
                    ('https://t/botin',       'V59 Test', 'Modelo Predator Elite',                                 1000, 'Botines',   'indumentaria', true, NULL, NULL),
                    ('https://t/antiparras',  'V59 Test', 'Antiparras adidas Ripstream Starter Jr',                1000, 'Otros',     'indumentaria', true, NULL, NULL),
                    ('https://t/sin-rubro',   'V59 Test', 'Short Real Madrid Local',                               1000, 'Short',     NULL,           true, NULL, NULL),
                    ('https://t/precedencia', 'V59 Test', 'Remera de rugby Boca Juniors',                         1000, 'Remera',    'indumentaria', true, NULL, NULL),
                    ('https://t/kennebec',    'V59 Test', 'Remera Timberland Kennebec River Tree',                 1000, 'Remera',    'indumentaria', true, NULL, NULL),
                    ('https://t/arena',       'V59 Test', 'Buzo color arena',                                      1000, 'Buzo',      'indumentaria', true, NULL, NULL),
                    ('https://t/golfista',    'V59 Test', 'Remera golfista urbana',                                1000, 'Remera',    'indumentaria', true, NULL, NULL),
                    ('https://t/bloqueada',   'V59 Test', 'Camiseta Boca Juniors Alternativa',                     1000, 'Remera',    'indumentaria', true, 'local', now()),
                    ('https://t/tech',        'V59 Test', 'Mouse gamer River Plate edition',                       1000, 'Mouse',     'tecnologia',   true, NULL, NULL)
                """);
    }

    /** Pending FK trigger events from the seed inserts would make the ALTER TABLE fail. */
    private static void aplicar(Statement st) throws Exception {
        st.execute("SET CONSTRAINTS ALL IMMEDIATE");
        st.execute(migracion());
    }

    private static String migracion() throws Exception {
        try (InputStream in = V59DeporteTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String deporte(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT deporte FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("row %s", url).isTrue();
            return rs.getString(1);
        }
    }

    private static String snapshot(Statement st) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ResultSet rs = st.executeQuery("SELECT url, deporte FROM productos ORDER BY url")) {
            while (rs.next()) sb.append(rs.getString(1)).append('=').append(rs.getString(2)).append(';');
        }
        return sb.toString();
    }
}
