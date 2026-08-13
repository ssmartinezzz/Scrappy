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
 * close-1nf-and-3nf-foundation extension, Phase 2 (V21, design E4).
 *
 * <p>Same contract as the other {@code V*RollbackRoundTripTest}s: applied
 * Flyway migrations are byte-frozen, so V21's rollback lives in
 * {@code docs/ARCHITECTURE.md} and this test executes that exact block
 * against the real schema inside a transaction it always rolls back.</p>
 *
 * <p>Lossless for the schema (the FK and the table come back exactly as
 * they were); does NOT attempt to undo the {@code marca = '' -> NULL}
 * backfill (not reversible, and the read boundary already treats both
 * identically — see the doc's prose).</p>
 */
@Epic("Persistence")
@Feature("Brand")
@Story("V21 rollback drops the marca table and its FK cleanly")
@DisplayName("V21 migration — the documented rollback actually runs")
class V21RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back drops the FK and the marca table without touching productos")
    void rollbackDropsMarcaTableAndFk() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                assertThat(tableExists(st, "marca")).isTrue();
                assertThat(constraintExists(st, "fk_productos_marca")).isTrue();

                st.execute(rollbackSql());

                assertThat(tableExists(st, "marca")).isFalse();
                assertThat(constraintExists(st, "fk_productos_marca")).isFalse();
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

    private static boolean constraintExists(Statement st, String constraintName) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema='public' AND constraint_name='" + constraintName + "'")) {
            return rs.next();
        }
    }

    /** The rollback block documented in {@code docs/ARCHITECTURE.md}, verbatim. */
    private static String rollbackSql() {
        String doc = readArchitectureDoc();
        String open = "-- >>> rollback:V21";
        String close = "-- <<< rollback:V21";
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
