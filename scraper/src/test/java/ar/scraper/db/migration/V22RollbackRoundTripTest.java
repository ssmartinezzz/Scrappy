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
 * close-1nf-and-3nf-foundation extension, Phase 3 (V22, design E2).
 *
 * <p>Same contract as the other {@code V*RollbackRoundTripTest}s: applied
 * Flyway migrations are byte-frozen, so V22's rollback lives in
 * {@code docs/ARCHITECTURE.md} and this test executes that exact block against
 * the real schema inside a transaction it always rolls back. The doc cannot
 * drift from what actually runs.</p>
 *
 * <p>V22 drops {@code productos.marca_premium} — a transitive dependency of
 * {@code sitio}, never of the key. The rollback restores the column with the
 * exact type and default it carried after V5. It deliberately does NOT restore
 * {@code sp_upsert_run}'s body, matching V21's precedent: the convention here
 * is to revert the <em>schema</em>, not the function. Nothing observable
 * changes either way, because after V22 no read path consults the column —
 * {@code marcaPremium} is resolved from {@code SiteRegistry}.</p>
 */
@Epic("Persistence")
@Feature("Site")
@Story("V22 rollback restores marca_premium with its V5 type and default")
@DisplayName("V22 migration — the documented rollback actually runs")
class V22RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back brings marca_premium back as boolean DEFAULT false")
    void rollbackRestoresMarcaPremiumColumn() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                assertThat(columnExists(st, "productos", "marca_premium"))
                        .as("V22 already dropped the column before this test runs")
                        .isFalse();

                st.execute(rollbackSql());

                assertThat(columnExists(st, "productos", "marca_premium")).isTrue();
                assertThat(dataTypeOf(st, "productos", "marca_premium"))
                        .as("restored with V5's type, not the pre-V5 integer")
                        .isEqualTo("boolean");
                assertThat(defaultOf(st, "productos", "marca_premium"))
                        .as("restored with V5's default")
                        .contains("false");
            } finally {
                c.rollback();
            }
        }
    }

    /**
     * The drop is what makes 3NF real here, so pin that the authoritative value
     * survives in {@code sitio} rather than having been deleted along with the
     * column. Without this, a rollback test alone would pass on a schema that
     * lost the data entirely.
     */
    @Test
    @DisplayName("sitio.es_premium still holds the authoritative premium flag")
    void premiumFlagSurvivesInSitio() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            assertThat(columnExists(st, "sitio", "es_premium")).isTrue();

            try (ResultSet rs = st.executeQuery(
                    "SELECT count(*) FROM sitio WHERE es_premium")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1))
                        .as("the seed marks at least one premium site (SITIOS_PREMIUM)")
                        .isGreaterThanOrEqualTo(1);
            }
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static boolean columnExists(Statement st, String tabla, String columna) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='" + tabla
                        + "' AND column_name='" + columna + "'")) {
            return rs.next();
        }
    }

    private static String dataTypeOf(Statement st, String tabla, String columna) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='" + tabla
                        + "' AND column_name='" + columna + "'")) {
            assertThat(rs.next()).as("%s.%s present", tabla, columna).isTrue();
            return rs.getString(1);
        }
    }

    private static String defaultOf(Statement st, String tabla, String columna) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema='public' AND table_name='" + tabla
                        + "' AND column_name='" + columna + "'")) {
            assertThat(rs.next()).as("%s.%s present", tabla, columna).isTrue();
            return String.valueOf(rs.getString(1));
        }
    }

    /** The rollback block documented in {@code docs/ARCHITECTURE.md}, verbatim. */
    private static String rollbackSql() {
        String doc = readArchitectureDoc();
        String open = "-- >>> rollback:V22";
        String close = "-- <<< rollback:V22";
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
