package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scrape-run-persistence-and-resume, slice 1 (task 1.3).
 *
 * <p>An applied Flyway migration is byte-frozen — adding so much as a comment
 * to a shipped {@code .sql} breaks checksum validation and the backend stops
 * booting — so {@code V29}'s rollback is documented in {@code docs/DATABASE.md}
 * instead. A documented rollback nobody runs is a guess, so this reads that
 * exact block from the doc and executes it against the real migrated schema
 * inside a transaction that is always rolled back.</p>
 *
 * <p>For a pure {@code DROP} the interesting property is not losslessness,
 * it is <b>containment</b>: the block must remove exactly its own two tables
 * and take nothing else with it. That is why the documented SQL drops the
 * child explicitly rather than reaching for {@code CASCADE}, which would
 * silently follow whatever comes to depend on these tables later.</p>
 */
@Epic("Persistence")
@Feature("Scrape run tracking")
@Story("V29 rollback runs, and takes nothing but its own two tables")
@DisplayName("V29 migration — the documented rollback actually runs, and is contained")
class V29RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back drops both run tables and leaves the rest of the schema standing")
    void rollbackDropsOnlyItsOwnTables() throws Exception {
        sembrarUnaCorrida();

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V29"));

                assertThat(existeTabla(st, "scrape_run_site")).isFalse();
                assertThat(existeTabla(st, "scrape_run")).isFalse();

                // The containment half. `sitio` is the one the FK pointed at, so
                // it is the table a stray CASCADE would have taken.
                assertThat(existeTabla(st, "sitio"))
                        .as("the rollback must not follow its own FK outwards")
                        .isTrue();
                assertThat(existeTabla(st, "productos")).isTrue();
                assertThat(existeTabla(st, "usuario")).isTrue();
                assertThat(existeTabla(st, "cron_jobs")).isTrue();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("The child is dropped before its parent — CASCADE is not doing the work")
    void rollbackOrdersTheDropsItself() {
        String sql = DocumentedRollback.sqlFor("V29");

        assertThat(sql.indexOf("scrape_run_site"))
                .as("ON DELETE CASCADE governs rows, not DROP TABLE: dropping "
                    + "scrape_run while scrape_run_site still references it fails")
                .isLessThan(sql.indexOf("DROP TABLE scrape_run;"));
        assertThat(sql)
                .as("an explicit order documents the dependency; CASCADE hides it "
                    + "and silently drops whatever else arrives later")
                .doesNotContain("CASCADE");
    }

    private void sembrarUnaCorrida() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("""
                INSERT INTO scrape_run (scrape_uuid, started_at, status)
                VALUES ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid, now(), 'RUNNING')
                """);
            st.execute("""
                INSERT INTO scrape_run_site (scrape_run_id, sitio_key, status)
                SELECT id, 'freres', 'PENDING' FROM scrape_run
                WHERE scrape_uuid = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid
                """);
        }
    }

    private static boolean existeTabla(Statement st, String tabla) throws Exception {
        try (ResultSet rs = st.executeQuery("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = '%s'
                """.formatted(tabla))) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }
}
