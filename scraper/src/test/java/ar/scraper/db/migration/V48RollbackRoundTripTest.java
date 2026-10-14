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

@DisplayName("V48 migration — gastar_todo: defaults to false, and the documented rollback is contained")
class V48RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("a row inserted without gastar_todo reads false")
    void elDefaultEsFalse() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO preferencia_armador (usuario_id, gama_id) "
                        + "SELECT '%s', id FROM gama WHERE nombre = 'ALTA'".formatted(usuario));
                try (ResultSet rs = st.executeQuery("SELECT gastar_todo FROM preferencia_armador")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getBoolean(1)).isFalse();
                }
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("Rolling back drops gastar_todo and leaves ram_minima_gb standing")
    void rollbackDropsOnlyItsOwnColumn() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V48"));

                assertThat(existeColumna(st, "gastar_todo")).isFalse();
                assertThat(existeColumna(st, "ram_minima_gb")).isTrue();
            } finally {
                c.rollback();
            }
        }
    }

    private static boolean existeColumna(Statement st, String columna) throws Exception {
        try (ResultSet rs = st.executeQuery("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'preferencia_armador' AND column_name = '%s'
                """.formatted(columna))) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }
}
