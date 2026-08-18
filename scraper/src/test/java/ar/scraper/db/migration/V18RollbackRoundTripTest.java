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
 * close-1nf-and-3nf-foundation, Phase 5 (V18, design DD3).
 *
 * <p>Same contract as the other {@code V*RollbackRoundTripTest}s: applied
 * Flyway migrations are byte-frozen, so V18's rollback lives in
 * {@code docs/DATABASE.md} and this test executes that exact block
 * against the real schema inside a transaction it always rolls back.</p>
 *
 * <p>Trivial by construction: {@code sitio} is read by nothing in this slice
 * (design DD3), so {@code DROP TABLE sitio} loses no downstream behavior —
 * only the table itself, which is exactly what the design says it is.</p>
 */
@Epic("Persistence")
@Feature("Site registry")
@Story("V18 rollback drops the sitio table cleanly")
@DisplayName("V18 migration — the documented rollback actually runs")
class V18RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back drops sitio without touching productos")
    void rollbackDropsSitioTable() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                assertThat(tableExists(st, "sitio")).isTrue();

                st.execute(rollbackSql());

                assertThat(tableExists(st, "sitio")).isFalse();
                assertThat(tableExists(st, "productos")).isTrue();
            } finally {
                c.rollback();
            }
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static boolean tableExists(Statement st, String tabla) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema='public' AND table_name='" + tabla + "'")) {
            return rs.next();
        }
    }

    /** El bloque de rollback documentado en {@code docs/DATABASE.md}, verbatim. */
    private static String rollbackSql() {
        return DocumentedRollback.sqlFor("V18");
    }
}
