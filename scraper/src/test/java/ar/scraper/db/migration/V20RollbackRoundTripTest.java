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
 * close-1nf-and-3nf-foundation extension, Phase 1 (V20, design E1).
 *
 * <p>Same contract as the other {@code V*RollbackRoundTripTest}s: applied
 * Flyway migrations are byte-frozen, so V20's rollback lives in
 * {@code docs/ARCHITECTURE.md} and this test executes that exact block
 * against the real schema inside a transaction it always rolls back.</p>
 *
 * <p>Lossless for every {@code dinamico} row that still has a matching
 * {@code sitio} row (the normal case — {@code guardarSitio} writes both in
 * the same call): the seeded row's {@code plataforma} comes back exactly.</p>
 */
@Epic("Persistence")
@Feature("Site registry")
@Story("V20 rollback restores sitios_dinamicos.plataforma")
@DisplayName("V20 migration — the documented rollback actually runs")
class V20RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back restores the plataforma column, backfilled from sitio")
    void rollbackRestoresPlataformaColumn() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                assertThat(columnExists(st, "sitios_dinamicos", "plataforma")).isFalse();

                st.execute("""
                    INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen)
                    VALUES ('RollbackSite', 'rollbacksite', 'shopify', false, NULL, 'dinamico')
                    """);
                st.execute("""
                    INSERT INTO sitios_dinamicos (nombre, url, created_at)
                    VALUES ('RollbackSite', 'https://rollbacksite.example', now())
                    """);

                st.execute(rollbackSql());

                assertThat(columnExists(st, "sitios_dinamicos", "plataforma")).isTrue();
                assertThat(valor(st,
                        "SELECT plataforma FROM sitios_dinamicos WHERE nombre = 'RollbackSite'"))
                        .isEqualTo("shopify");
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

    private static boolean columnExists(Statement st, String tabla, String columna) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='" + tabla + "' "
                + "AND column_name='" + columna + "'")) {
            return rs.next();
        }
    }

    /** The rollback block documented in {@code docs/ARCHITECTURE.md}, verbatim. */
    private static String rollbackSql() {
        String doc = readArchitectureDoc();
        String open = "-- >>> rollback:V20";
        String close = "-- <<< rollback:V20";
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
