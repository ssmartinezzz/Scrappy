package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V41}'s rollback is documented in {@code docs/DATABASE.md} (a shipped migration is
 * byte-frozen — see {@code DocumentedRollback}); this executes that exact block inside a
 * transaction that always rolls back. V41 is the newest migration, so the block runs against
 * the full schema, and it is the first block a chain of rollbacks runs.
 */
@DisplayName("V41 migration — the documented rollback actually runs, and is contained")
class V41RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back drops the three triggers and the function, leaving the tables standing")
    void rollbackDropsOnlyItsOwnObjects() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                assertThat(objetos(st)).isEqualTo(4);

                st.execute(DocumentedRollback.sqlFor("V41"));

                assertThat(objetos(st)).isZero();
                assertThat(existe(st, "scrape_run")).isTrue();
                assertThat(existe(st, "scrape_run_site")).isTrue();
                assertThat(existe(st, "cron_executions")).isTrue();
                assertThat(existe(st, "catalog_version")).as("V40 is untouched").isTrue();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("The block drops every trigger before the function and never uses CASCADE")
    void rollbackOrdersTheDropsItself() {
        String sql = DocumentedRollback.sqlFor("V41");
        int lastTrigger = sql.lastIndexOf("DROP TRIGGER");
        int function = sql.indexOf("DROP FUNCTION notify_status_change()");

        assertThat(function).isGreaterThan(lastTrigger);
        assertThat(sql).doesNotContain("CASCADE");
    }

    private static int objetos(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("""
                SELECT (SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'trg_status_notify_%')
                     + (SELECT count(*) FROM pg_proc WHERE proname = 'notify_status_change')
                """)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean existe(Statement st, String tabla) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT to_regclass('public." + tabla + "') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}
