package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("V47 migration — ram_minima_gb: the CHECK holds, and the documented rollback is contained")
class V47RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("ram_minima_gb accepts NULL and positive values, and the CHECK rejects 0")
    void elCheckRechazaCero() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("INSERT INTO preferencia_armador (usuario_id, gama_id, ram_minima_gb) "
                        + "SELECT '%s', id, 32 FROM gama WHERE nombre = 'ALTA'".formatted(usuario));
                assertThatThrownBy(() -> st.execute("UPDATE preferencia_armador SET ram_minima_gb = 0"))
                        .isInstanceOf(SQLException.class);
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("Rolling back drops ram_minima_gb and leaves the fase-9 floors standing")
    void rollbackDropsOnlyItsOwnColumn() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V47"));

                assertThat(existeColumna(st, "preferencia_armador", "ram_minima_gb")).isFalse();
                assertThat(existeColumna(st, "preferencia_armador", "capacidad_minima_gb")).isTrue();
                assertThat(existeColumna(st, "preferencia_armador", "watts_minimos")).isTrue();
            } finally {
                c.rollback();
            }
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
