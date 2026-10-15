package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V51 migration — the retention index on notificacion_precio and the documented rollback")
class V51IndiceDePurgaTest extends PostgresTestBase {

    @Test
    @DisplayName("a partial expression index covers only finished rows, ordered by the timestamp the purge compares")
    void indice() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            String def = escalar(st, "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_notificacion_precio_terminadas'");
            assertThat(def).contains("COALESCE(enviada_at, proximo_intento_at)").contains("ENVIADA").contains("FALLIDA");
        }
    }

    @Test
    @DisplayName("the documented rollback drops the index and leaves the outbox and V50's index in place")
    void rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V51"));

                assertThat(escalar(st, "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_notificacion_precio_terminadas'"))
                        .isEqualTo("0");
                assertThat(escalar(st, "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_notificacion_precio_en_vuelo'"))
                        .isEqualTo("1");
                assertThat(escalar(st, "SELECT to_regclass('notificacion_precio') IS NOT NULL")).isEqualTo("t");
            } finally {
                c.rollback();
            }
        }
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
