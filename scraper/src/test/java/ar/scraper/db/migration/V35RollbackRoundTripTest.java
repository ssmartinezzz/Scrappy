package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code V35}'s rollback is documented in {@code docs/DATABASE.md} (a shipped
 * migration is byte-frozen — see {@code DocumentedRollback}), so this executes
 * that exact block against the real migrated schema, inside a transaction
 * that always rolls back.
 *
 * <p>Only T4's objects: {@code preferencia_armador}, {@code gama} and
 * {@code saved_pcs.gama_id}. T5 extends this same {@code V35} with
 * {@code producto_tech_specs} and the other five lookups later.</p>
 */
@DisplayName("V35 migration — the documented rollback actually runs, and is contained")
class V35RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back drops preferencia_armador and gama, and the saved_pcs column, "
            + "leaving the rest of the schema standing")
    void rollbackDropsOnlyItsOwnObjects() throws Exception {
        sembrarUnaPreferencia();

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V35"));

                assertThat(existeTabla(st, "preferencia_armador")).isFalse();
                assertThat(existeTabla(st, "gama")).isFalse();

                assertThat(existeTabla(st, "saved_pcs")).isTrue();
                assertThat(existeColumna(st, "saved_pcs", "gama_id")).isFalse();

                assertThat(existeTabla(st, "productos")).isTrue();
                assertThat(existeTabla(st, "usuario")).isTrue();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("preferencia_armador and the saved_pcs column drop come before dropping gama, "
            + "and there is no CASCADE doing the work")
    void rollbackOrdersTheDropsItself() {
        String sql = DocumentedRollback.sqlFor("V35");

        assertThat(sql.indexOf("preferencia_armador"))
                .as("preferencia_armador.gama_id references gama(id): dropping gama first "
                        + "fails while preferencia_armador still references it")
                .isLessThan(sql.indexOf("DROP TABLE gama;"));
        assertThat(sql.indexOf("DROP COLUMN gama_id"))
                .as("saved_pcs.gama_id references gama(id) too")
                .isLessThan(sql.indexOf("DROP TABLE gama;"));
        assertThat(sql).doesNotContain("CASCADE");
    }

    private void sembrarUnaPreferencia() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute("""
                    INSERT INTO preferencia_armador (usuario_id, gama_id, con_gpu)
                    SELECT '%s', g.id, false FROM gama g WHERE g.nombre = 'ALTA'
                    """.formatted(usuario));
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

    private static boolean existeColumna(Statement st, String tabla, String columna) throws Exception {
        try (ResultSet rs = st.executeQuery("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = '%s' AND column_name = '%s'
                """.formatted(tabla, columna))) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }
}
