package ar.scraper.db.migration;

import ar.scraper.db.DatabaseService;
import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * normalize-db-schema-fks-1nf, slice A.4 (V8).
 *
 * <p>Same contract as {@code V7RollbackRoundTripTest}: an applied Flyway
 * migration is byte-frozen, so V8's rollback is documented in
 * {@code docs/ARCHITECTURE.md}, and this test executes that exact block against
 * the real schema inside a transaction it always rolls back — so the rollback
 * is a verified statement, not a plausible one.</p>
 *
 * <p>It also pins the honest part of the story: going back to TEXT preserves
 * the INSTANT but NOT the original string shape ({@code 2026-08-11 20:15:00+00},
 * not {@code 2026-08-11 20:15:00}). Reverting the schema without reverting the
 * application commit leaves the old code parsing something it never expected.</p>
 */
@Epic("Persistence")
@Feature("Column domains")
@Story("V8 rollback returns every timestamp column to TEXT")
@DisplayName("V8 migration — the documented rollback actually runs")
class V8RollbackRoundTripTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    @Test
    @DisplayName("Rolling back returns the columns to TEXT and keeps the instant")
    void rollbackReturnsColumnsToTextPreservingTheInstant() throws Exception {
        long jobId = db.insertCronJob("Job", 1000, 50000, List.of("Freres"),
                false, true, "0 0 3 * * *", true, "2026-07-05T03:00:00");
        String esperado = db.getCronJob(jobId).orElseThrow().nextRunAt();

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(rollbackSql());

                assertThat(dataType(st, "cron_jobs", "next_run_at")).isEqualTo("text");
                assertThat(dataType(st, "favoritos", "added_at")).isEqualTo("text");
                assertThat(dataType(st, "agent_reclassify_audit", "applied_at")).isEqualTo("text");

                // Same instant...
                String reparseado = valor(st,
                        "SELECT to_char(next_run_at::timestamptz AT TIME ZONE 'UTC', "
                                + "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM cron_jobs WHERE id = " + jobId);
                assertThat(Instant.parse(reparseado)).isEqualTo(Instant.parse(esperado));

                // ...but NOT the same string: TEXT now carries the offset the
                // TIMESTAMPTZ rendering appends, which no pre-V8 writer ever wrote.
                String crudo = valor(st, "SELECT next_run_at FROM cron_jobs WHERE id = " + jobId);
                assertThat(crudo).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}[+-]\\d{2}");
            } finally {
                c.rollback();
            }
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static String valor(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as("query returned a row: %s", sql).isTrue();
            return rs.getString(1);
        }
    }

    private static String dataType(Statement st, String tabla, String columna) throws Exception {
        return valor(st, "SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='" + tabla + "' "
                + "AND column_name='" + columna + "'");
    }

    /** The rollback block documented in {@code docs/ARCHITECTURE.md}, verbatim. */
    private static String rollbackSql() {
        String doc = readArchitectureDoc();
        String open = "-- >>> rollback:V8";
        String close = "-- <<< rollback:V8";
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
