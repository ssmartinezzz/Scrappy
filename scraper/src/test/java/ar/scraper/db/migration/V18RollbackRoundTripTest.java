package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation, Phase 5 (V18, design DD3).
 *
 * <p>Same contract as the other {@code V*RollbackRoundTripTest}s: applied
 * Flyway migrations are byte-frozen, so V18's rollback lives in
 * {@code docs/ARCHITECTURE.md} and this test executes that exact block
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

    /** The rollback block documented in {@code docs/ARCHITECTURE.md}, verbatim. */
    private static String rollbackSql() {
        String doc = readArchitectureDoc();
        String open = "-- >>> rollback:V18";
        String close = "-- <<< rollback:V18";
        int start = doc.indexOf(open);
        assertThat(start).as("opening marker '%s' present in docs/ARCHITECTURE.md", open).isNotEqualTo(-1);
        int end = doc.indexOf(close, start);
        assertThat(end).as("closing marker '%s' present in docs/ARCHITECTURE.md", close).isNotEqualTo(-1);
        String sql = doc.substring(start + open.length(), end).trim();
        assertThat(sql).as("the documented rollback block is not empty").isNotEmpty();
        return sql;
    }

    private static String readArchitectureDoc() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("docs").resolve("ARCHITECTURE.md");
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("docs/ARCHITECTURE.md not found walking up from "
                + System.getProperty("user.dir"));
    }
}
