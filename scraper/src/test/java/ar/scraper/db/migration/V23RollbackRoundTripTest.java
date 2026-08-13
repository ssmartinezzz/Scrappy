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
 * close-1nf-and-3nf-foundation extension, design E7 (V23).
 *
 * <p>Same contract as the other {@code V*RollbackRoundTripTest}s: applied
 * migrations are byte-frozen, so the rollback lives in
 * {@code docs/ARCHITECTURE.md} and this test executes that exact block against
 * the real schema inside a transaction it always rolls back.</p>
 *
 * <p>The rollback drops the constraint and nothing else. It deliberately does
 * not delete the {@code origen='historico'} rows the backfill may have
 * created: those are real, observed site identities, not migration debris, and
 * deleting them would discard information nothing else holds.</p>
 */
@Epic("Persistence")
@Feature("Site")
@Story("V23 rollback drops the productos.sitio FK and leaves the rows alone")
@DisplayName("V23 migration — the documented rollback actually runs")
class V23RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back drops fk_productos_sitio without touching sitio's rows")
    void rollbackDropsTheForeignKey() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                assertThat(constraintExists(st, "fk_productos_sitio")).isTrue();
                int sitiosAntes = contarSitios(st);

                st.execute(rollbackSql());

                assertThat(constraintExists(st, "fk_productos_sitio")).isFalse();
                assertThat(contarSitios(st))
                        .as("el rollback suelta la restricción, no borra identidades de sitio observadas")
                        .isEqualTo(sitiosAntes);
            } finally {
                c.rollback();
            }
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static boolean constraintExists(Statement st, String constraintName) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_schema='public' AND constraint_name='" + constraintName + "'")) {
            return rs.next();
        }
    }

    private static int contarSitios(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM sitio")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    /** The rollback block documented in {@code docs/ARCHITECTURE.md}, verbatim. */
    private static String rollbackSql() {
        String doc = readArchitectureDoc();
        String open = "-- >>> rollback:V23";
        String close = "-- <<< rollback:V23";
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
