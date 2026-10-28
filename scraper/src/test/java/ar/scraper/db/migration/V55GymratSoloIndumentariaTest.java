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
 * Flyway already ran V55 against an empty catalog, so the test replays the migration's own SQL
 * over seeded rows: that is the only way to see what it does to data.
 */
@DisplayName("V55 — gymrat is cleared outside indumentaria, idempotently, and nowhere else")
class V55GymratSoloIndumentariaTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V55__gymrat_solo_indumentaria.sql";

    @Test
    @DisplayName("Clears gymrat on non-indumentaria rows and leaves indumentaria and NULL rubro alone")
    void limpiaSoloFueraDeIndumentaria() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                sembrar(st);

                st.execute(migracion());

                assertThat(gymrat(st, "https://t/monitor")).isFalse();
                assertThat(gymrat(st, "https://t/monitor-inactivo")).isFalse();
                assertThat(gymrat(st, "https://t/calza")).isTrue();
                assertThat(gymrat(st, "https://t/sin-rubro")).isTrue();
                assertThat(gymrat(st, "https://t/mouse")).isFalse();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("A second run touches no row")
    void esIdempotente() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                sembrar(st);
                st.execute(migracion());

                assertThat(st.executeUpdate(migracion())).isZero();
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
                st.execute(DocumentedRollback.sqlFor("V55"));
            } finally {
                c.rollback();
            }
        }
    }

    private static void sembrar(Statement st) throws Exception {
        st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                + "VALUES ('V55 Test', 'v55test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
        st.execute("""
                INSERT INTO productos (url, sitio, nombre, precio, rubro, gymrat, activo) VALUES
                    ('https://t/monitor',          'V55 Test', 'Monitor 24 Performance', 1000, 'tecnologia',   true,  true),
                    ('https://t/monitor-inactivo', 'V55 Test', 'Monitor 19 Performance', 1000, 'tecnologia',   true,  false),
                    ('https://t/calza',            'V55 Test', 'Calza Performance',      1000, 'indumentaria', true,  true),
                    ('https://t/sin-rubro',        'V55 Test', 'Remera Training',        1000, NULL,           true,  true),
                    ('https://t/mouse',            'V55 Test', 'Mouse',                  1000, 'tecnologia',   false, true)
                """);
    }

    private static String migracion() throws Exception {
        try (InputStream in = V55GymratSoloIndumentariaTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean gymrat(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT gymrat FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("row %s", url).isTrue();
            return rs.getBoolean(1);
        }
    }
}
