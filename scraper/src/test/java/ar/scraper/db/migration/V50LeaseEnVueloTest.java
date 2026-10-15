package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V50 migration — notificacion_precio.lease_hasta and the documented rollback")
class V50LeaseEnVueloTest extends PostgresTestBase {

    @Test
    @DisplayName("lease_hasta exists, is nullable, and a partial index covers only the leased rows")
    void columnaEIndice() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(escalar(st, """
                    SELECT is_nullable FROM information_schema.columns
                     WHERE table_name = 'notificacion_precio' AND column_name = 'lease_hasta'
                    """)).isEqualTo("YES");
            assertThat(escalar(st, """
                    SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_notificacion_precio_en_vuelo'
                    """)).contains("lease_hasta").contains("WHERE");
        }
    }

    @Test
    @DisplayName("the documented rollback drops the column and its index and leaves the outbox in place")
    void rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V50"));

                assertThat(escalar(st, """
                        SELECT count(*) FROM information_schema.columns
                         WHERE table_name = 'notificacion_precio' AND column_name = 'lease_hasta'
                        """)).isEqualTo("0");
                assertThat(escalar(st, "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_notificacion_precio_en_vuelo'"))
                        .isEqualTo("0");
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
