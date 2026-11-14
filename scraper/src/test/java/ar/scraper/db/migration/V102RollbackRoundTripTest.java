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
@DisplayName("V102 — el rollback documentado corre y deja el esquema de V101")
class V102RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("quita la red, borra las promos con red, restaura la clave de V101; se revierte con la transacción")
    void rollbackRuns() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                String sitio = text(st, "SELECT nombre FROM sitio ORDER BY nombre LIMIT 1");
                st.execute("SELECT sp_upsert_promos('[{\"sitio\":\"" + sitio + "\",\"medioPago\":\"Macro\","
                        + "\"tipoMedio\":\"banco\",\"tipo\":\"cuotas_sin_interes\",\"cuotas\":12,\"alcance\":\"sitio\","
                        + "\"red\":\"Visa\"},{\"sitio\":\"" + sitio + "\",\"medioPago\":\"Macro\",\"tipoMedio\":\"banco\","
                        + "\"tipo\":\"cuotas_sin_interes\",\"cuotas\":12,\"alcance\":\"sitio\","
                        + "\"red\":\"Mastercard\"},{\"sitio\":\"" + sitio + "\",\"medioPago\":\"Santander\","
                        + "\"tipo\":\"cuotas_sin_interes\",\"cuotas\":6,\"alcance\":\"sitio\"}]'::jsonb, NULL)");

                st.execute(DocumentedRollback.sqlFor("V102"));

                assertThat(scalar(st, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'promo_financiacion'"
                        + " AND column_name = 'red_medio_pago_id'")).isZero();
                assertThat(scalar(st, "SELECT count(*) FROM promo_financiacion")).as("only the red-less row survives").isEqualTo(1);
                assertThat(scalar(st, "SELECT count(*) FROM pg_indexes WHERE indexname = 'uq_promo_financiacion_natural'"
                        + " AND indexdef NOT LIKE '%red_medio_pago_id%'")).isEqualTo(1);
                assertThat(scalar(st, "SELECT count(*) FROM pg_proc WHERE proname = 'sp_upsert_promos'")).isZero();
                assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '102'"
                        + " OR description = 'sp upsert promos'")).isZero();
                assertThat(scalar(st, "SELECT count(*) FROM flyway_schema_history WHERE version = '101'")).isEqualTo(1);
            } finally {
                c.rollback();
            }
        }
    }

    private static String text(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
