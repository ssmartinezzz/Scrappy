package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V34}'s rollback is documented in {@code docs/DATABASE.md} (a shipped
 * migration is byte-frozen — see {@code DocumentedRollback}), so this executes
 * that exact block against the real migrated schema, inside a transaction
 * that always rolls back.
 */
@DisplayName("V34 migration — the documented rollback actually runs, and is contained")
class V34RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back drops both saved-PCs tables and leaves the rest of the schema standing")
    void rollbackDropsOnlyItsOwnTables() throws Exception {
        sembrarUnPc();

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V34"));

                assertThat(existeTabla(st, "saved_pc_item")).isFalse();
                assertThat(existeTabla(st, "saved_pcs")).isFalse();

                assertThat(existeTabla(st, "productos")).isTrue();
                assertThat(existeTabla(st, "saved_outfits")).isTrue();
                assertThat(existeTabla(st, "usuario")).isTrue();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("The child is dropped before its parent — no CASCADE doing the work")
    void rollbackOrdersTheDropsItself() {
        String sql = DocumentedRollback.sqlFor("V34");

        assertThat(sql.indexOf("saved_pc_item"))
                .as("saved_pc_item.pc_id references saved_pcs(id): dropping saved_pcs "
                    + "first fails while saved_pc_item still references it")
                .isLessThan(sql.indexOf("DROP TABLE saved_pcs;"));
        assertThat(sql).doesNotContain("CASCADE");
    }

    private void sembrarUnPc() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO saved_pcs (nombre, presupuesto, con_gpu, total_estimado)
                    VALUES ('Test', 500000, false, 250000)
                    """);
            st.execute("""
                    INSERT INTO saved_pc_item (pc_id, posicion, slot, url)
                    SELECT id, 1, 'mother', 'https://t/mb' FROM saved_pcs ORDER BY id DESC LIMIT 1
                    """);
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
