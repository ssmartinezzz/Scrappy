package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V42}'s rollback is documented in {@code docs/DATABASE.md} (a shipped migration is
 * byte-frozen — see {@code DocumentedRollback}); this executes that exact block inside a
 * transaction that always rolls back. V42 is the newest migration, so its block runs first in a
 * chain of rollbacks, before V41's.
 */
@DisplayName("V42 migration — the documented rollback actually runs, and is contained")
class V42RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back drops the two CHECK constraints and nothing else")
    void rollbackDropsOnlyItsOwnConstraints() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                assertThat(constraints(st)).isEqualTo(2);

                st.execute(DocumentedRollback.sqlFor("V42"));

                assertThat(constraints(st)).isZero();
                assertThat(existe(st, "productos")).isTrue();
                assertThat(existe(st, "catalog_version")).as("V40 is untouched").isTrue();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("The block never uses CASCADE")
    void rollbackNeverCascades() {
        assertThat(DocumentedRollback.sqlFor("V42")).doesNotContain("CASCADE");
    }

    private static int constraints(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("""
                SELECT count(*) FROM pg_constraint
                WHERE conrelid = 'productos'::regclass
                  AND conname IN ('chk_productos_nombre_not_blank', 'chk_productos_sitio_not_blank')
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
