package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V33}'s rollback is documented in {@code docs/DATABASE.md} (a shipped
 * migration is byte-frozen — see {@code DocumentedRollback}), so this executes
 * that exact block against the real migrated schema, inside a transaction
 * that always rolls back.
 */
@DisplayName("V33 migration — the documented rollback actually runs, and is contained")
class V33RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back drops both indices tables and leaves the rest of the schema standing")
    void rollbackDropsOnlyItsOwnTables() throws Exception {
        sembrarUnValor();

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V33"));

                assertThat(existeTabla(st, "indice_valor")).isFalse();
                assertThat(existeTabla(st, "indice")).isFalse();

                assertThat(existeTabla(st, "productos")).isTrue();
                assertThat(existeTabla(st, "categoria")).isTrue();
                assertThat(existeTabla(st, "sitio")).isTrue();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("The child is dropped before its parent — no CASCADE doing the work")
    void rollbackOrdersTheDropsItself() {
        String sql = DocumentedRollback.sqlFor("V33");

        assertThat(sql.indexOf("indice_valor"))
                .as("indice_valor.indice references indice(codigo): dropping indice "
                    + "first fails while indice_valor still references it")
                .isLessThan(sql.indexOf("DROP TABLE indice;"));
        assertThat(sql).doesNotContain("CASCADE");
    }

    private void sembrarUnValor() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO indice_valor (indice, fecha, valor) VALUES ('IPC', '2026-01-31', 100.0)");
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
