package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * normalize-db-schema-fks-1nf, slice B (V7), design D5.
 *
 * <p>Runs V7's backfill statements — read verbatim from the shipped migration
 * resource, so tested SQL and shipped SQL cannot diverge — against
 * LEGACY-shaped rows. By the time any test runs, the real {@code productos}
 * has already lost {@code talles}/{@code ml_badge}, so the legacy shape is
 * rebuilt in a scratch schema inside a transaction that is always rolled back:
 * the schema, the tables and the {@code SET LOCAL search_path} all vanish with
 * it, leaving nothing behind on a pooled connection.</p>
 *
 * <p>The cases that matter are the ones a live install can actually hold and a
 * happy-path backfill would abort or silently drop: a non-JSON {@code talles}
 * value ({@code talles} is unconstrained TEXT), the {@code ml_badge DEFAULT ''}
 * that {@code string_to_table} turns into one empty element, and the 6914
 * {@code activo=false} rows — {@code obtenerProducto} does not filter
 * {@code activo}, so a live-only backfill would silently empty the sizes of
 * every discontinued product.</p>
 */
@Epic("Persistence")
@Feature("Multi-value normalization")
@Story("V7 backfill from the legacy TEXT columns")
@DisplayName("V7 migration — backfill from talles/ml_badge into the child tables")
class BackfillSqlTest extends PostgresTestBase {

    private static final String V7 = "/db/migration/V7__product_multivalue_child_tables.sql";

    @Test
    @DisplayName("Sizes are backfilled in order, skipping non-JSON, NULL and blank values")
    void tallesBackfillHandlesEveryLegacyShape() throws Exception {
        withLegacyRows(st -> {
            st.execute(backfillStatement("producto_talle"));

            assertThat(childRows(st, "producto_talle", "talle", "https://site.com/a"))
                    .containsExactly("1:S", "2:M", "3:L");
            assertThat(childRows(st, "producto_talle", "talle", "https://site.com/b-null")).isEmpty();
            assertThat(childRows(st, "producto_talle", "talle", "https://site.com/c-garbage")).isEmpty();
            assertThat(childRows(st, "producto_talle", "talle", "https://site.com/d-empty-array")).isEmpty();
            assertThat(childRows(st, "producto_talle", "talle", "https://site.com/e-blank-element"))
                    .containsExactly("1:S");
        });
    }

    @Test
    @DisplayName("Discontinued products are backfilled too — they are still readable by URL")
    void tallesBackfillCoversInactiveProducts() throws Exception {
        withLegacyRows(st -> {
            st.execute(backfillStatement("producto_talle"));

            assertThat(childRows(st, "producto_talle", "talle", "https://site.com/f-inactivo"))
                    .containsExactly("1:38", "2:40");
        });
    }

    @Test
    @DisplayName("Badges are backfilled principal-first, and the empty default yields no rows")
    void badgeBackfillPreservesPrincipalFirstOrder() throws Exception {
        withLegacyRows(st -> {
            st.execute(backfillStatement("producto_badge"));

            assertThat(childRows(st, "producto_badge", "badge", "https://site.com/a"))
                    .containsExactly("1:verified_deal", "2:trending");
            assertThat(childRows(st, "producto_badge", "badge", "https://site.com/b-null")).isEmpty();
            assertThat(childRows(st, "producto_badge", "badge", "https://site.com/c-garbage")).isEmpty();
            assertThat(childRows(st, "producto_badge", "badge", "https://site.com/d-empty-array"))
                    .containsExactly("1:all_time_low");
        });
    }

    // ─── harness ───────────────────────────────────────────────────────────

    private interface ProbeBody {
        void run(Statement st) throws Exception;
    }

    /**
     * Rebuilds the pre-V7 shape of {@code productos} plus both child tables in
     * a scratch schema, runs {@code body}, and always rolls back.
     */
    private void withLegacyRows(ProbeBody body) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("CREATE SCHEMA backfill_probe");
                st.execute("SET LOCAL search_path TO backfill_probe");
                st.execute("""
                        CREATE TABLE productos (
                            url      TEXT PRIMARY KEY,
                            talles   TEXT,
                            ml_badge TEXT DEFAULT '',
                            activo   BOOLEAN DEFAULT true
                        )""");
                st.execute("""
                        CREATE TABLE producto_talle (
                            url      TEXT     NOT NULL REFERENCES productos(url) ON DELETE CASCADE,
                            posicion SMALLINT NOT NULL,
                            talle    TEXT     NOT NULL,
                            PRIMARY KEY (url, posicion)
                        )""");
                st.execute("""
                        CREATE TABLE producto_badge (
                            url      TEXT     NOT NULL REFERENCES productos(url) ON DELETE CASCADE,
                            posicion SMALLINT NOT NULL,
                            badge    TEXT     NOT NULL,
                            PRIMARY KEY (url, posicion)
                        )""");
                st.execute("""
                        INSERT INTO productos (url, talles, ml_badge, activo) VALUES
                          ('https://site.com/a',             '["S","M","L"]', 'verified_deal,trending', true),
                          ('https://site.com/b-null',        NULL,            NULL,                     true),
                          ('https://site.com/c-garbage',     'talle unico',   '',                       true),
                          ('https://site.com/d-empty-array', '[]',            'all_time_low',           true),
                          ('https://site.com/e-blank-element','["S",""]',     '',                       true),
                          ('https://site.com/f-inactivo',    '["38","40"]',   '',                       false)
                        """);

                body.run(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static List<String> childRows(Statement st, String table, String valueColumn, String url)
            throws Exception {
        List<String> rows = new ArrayList<>();
        try (ResultSet rs = st.executeQuery(
                "SELECT posicion, " + valueColumn + " FROM " + table
                        + " WHERE url = '" + url + "' ORDER BY posicion")) {
            while (rs.next()) {
                rows.add(rs.getInt(1) + ":" + rs.getString(2));
            }
        }
        return rows;
    }

    /** The exact statement V7 ships, delimited by its own {@code backfill:<table>} markers. */
    private static String backfillStatement(String table) {
        String sql = readClasspathResource(V7);
        String open = "-- >>> backfill:" + table;
        String close = "-- <<< backfill:" + table;
        int start = sql.indexOf(open);
        assertThat(start).as("opening marker '%s' present in %s", open, V7).isNotEqualTo(-1);
        int end = sql.indexOf(close, start);
        assertThat(end).as("closing marker '%s' present in %s", close, V7).isNotEqualTo(-1);
        String statement = sql.substring(start + open.length(), end).trim();
        assertThat(statement).as("statement between the %s markers is not empty", table).isNotEmpty();
        return statement;
    }

    private static String readClasspathResource(String path) {
        try (InputStream in = BackfillSqlTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, "Missing classpath resource: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
