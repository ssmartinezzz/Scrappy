package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Base de datos")
@Feature("Rollback documentado")
@DisplayName("V101 — el rollback documentado corre y deja el esquema previo")
class V101RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("quita la función, las tres tablas y las filas de flyway; se revierte con la transacción")
    void rollbackRuns() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V101"));
                assertThat(scalar(st, "SELECT count(*) FROM pg_tables WHERE tablename IN"
                        + " ('medio_pago','promo_financiacion','promo_financiacion_producto')")).isZero();
                assertThat(scalar(st, "SELECT count(*) FROM pg_proc WHERE proname = 'sp_upsert_promos'")).isZero();
                assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '101'"
                        + " OR description = 'sp upsert promos'")).isZero();
            } finally {
                c.rollback();
            }
        }
    }

    private static long scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
