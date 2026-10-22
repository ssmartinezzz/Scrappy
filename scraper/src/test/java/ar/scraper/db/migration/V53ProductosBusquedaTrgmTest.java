package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Persistence")
@Feature("Catalog search")
@Story("V53 gives the nombre/marca ILIKE search a trigram index")
@DisplayName("V53 — pg_trgm GIN indexes on productos(nombre, marca)")
class V53ProductosBusquedaTrgmTest extends PostgresTestBase {

    /** The exact predicate CatalogQueryRepository emits for the q filter. */
    private static final String Q_PREDICATE =
            "SELECT count(*) FROM productos p "
                    + "WHERE (p.nombre ILIKE '%' || ? || '%' OR p.marca ILIKE '%' || ? || '%')";

    @Test
    @DisplayName("both columns have a GIN index on gin_trgm_ops")
    void bothColumnsHaveATrigramIndex() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            List<String> defs = column(st, "SELECT indexdef FROM pg_indexes WHERE tablename = 'productos'");
            assertThat(defs).anyMatch(d -> d.contains("USING gin (nombre gin_trgm_ops)"));
            assertThat(defs).anyMatch(d -> d.contains("USING gin (marca gin_trgm_ops)"));
        }
    }

    @Test
    @DisplayName("the parameterised q predicate, as a generic plan, is answered by both indexes")
    void theQPredicateCanUseTheIndexes() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL enable_seqscan = off");
                st.execute("SET LOCAL plan_cache_mode = force_generic_plan");
                try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + Q_PREDICATE)) {
                    ps.setString(1, "monky");
                    ps.setString(2, "monky");
                    StringBuilder plan = new StringBuilder();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) plan.append(rs.getString(1)).append('\n');
                    }
                    assertThat(plan.toString())
                            .contains("Bitmap Index Scan")
                            .contains("idx_productos_nombre_trgm")
                            .contains("idx_productos_marca_trgm");
                }
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("the documented rollback drops both indexes and keeps the extension")
    void rollbackDropsTheIndexesOnly() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V53"));
                assertThat(column(st, "SELECT indexname FROM pg_indexes WHERE tablename = 'productos'"))
                        .doesNotContain("idx_productos_nombre_trgm", "idx_productos_marca_trgm");
                assertThat(column(st, "SELECT extname FROM pg_extension")).contains("pg_trgm");
            } finally {
                c.rollback();
            }
        }
    }

    private static List<String> column(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        }
    }
}
