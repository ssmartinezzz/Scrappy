package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation, Phase 3 (V16, design DD6).
 *
 * <p>Same contract as {@code V7RollbackRoundTripTest}/{@code V8RollbackRoundTripTest}:
 * applied Flyway migrations are byte-frozen, so V16's rollback lives in
 * {@code docs/DATABASE.md} and this test executes that exact block
 * against the real schema inside a transaction it always rolls back.</p>
 *
 * <p>Lossless in shape (the 12 columns come back as a JSON blob); NOT lossless
 * in key vocabulary (the keys come back as V13 canon, not {@code norm_cat}
 * output) — harmless, since the table is fully regenerated on the next ML run.</p>
 */
@Epic("Persistence")
@Feature("categoria_stats")
@Story("V16 rollback returns categoria_stats to a payload blob")
@DisplayName("V16 migration — the documented rollback actually runs")
class V16RollbackRoundTripTest extends PostgresTestBase {

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
    }

    @Test
    @DisplayName("Rolling back restores the payload column with the same 12 fields inside")
    void rollbackRestoresPayloadColumn() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement insert = c.createStatement()) {
                insert.execute("""
                    INSERT INTO categoria_stats
                        (categoria, n, mean, median, mode, std, cv, q1, q3, iqr, mad, fence_low, fence_high, updated_at)
                    VALUES
                        ('Remera', 42, 15000, 14500, 12000, 3200, 21.3, 11000, 18000, 7000, 2500, 500, 28500, now())
                    """);
            }

            try (Statement st = c.createStatement()) {
                st.execute(rollbackSql());

                assertThat(dataType(st, "categoria_stats", "payload")).isEqualTo("text");
                assertThat(columnExists(st, "categoria_stats", "n")).isFalse();

                String payload = valor(st, "SELECT payload FROM categoria_stats WHERE categoria = 'Remera'");
                JsonNode node = mapper.readTree(payload);
                assertThat(node.get("n").asInt()).isEqualTo(42);
                assertThat(node.get("mean").asLong()).isEqualTo(15000);
                assertThat(node.get("cv").asDouble()).isEqualTo(21.3);
                assertThat(node.get("fence_high").asLong()).isEqualTo(28500);
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

    private static String dataType(Statement st, String tabla, String columna) throws Exception {
        return valor(st, "SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='" + tabla + "' "
                + "AND column_name='" + columna + "'");
    }

    private static boolean columnExists(Statement st, String tabla, String columna) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='" + tabla + "' "
                + "AND column_name='" + columna + "'")) {
            return rs.next();
        }
    }

    /** El bloque de rollback documentado en {@code docs/DATABASE.md}, verbatim. */
    private static String rollbackSql() {
        return DocumentedRollback.sqlFor("V16");
    }
}
