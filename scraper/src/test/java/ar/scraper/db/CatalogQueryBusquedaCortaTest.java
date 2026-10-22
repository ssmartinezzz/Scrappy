package ar.scraper.db;

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

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Persistence")
@Feature("Catalog search")
@Story("A term too short for a trigram never goes through the trigram index")
@DisplayName("CatalogQueryRepository — q predicate vs the V53 trigram indexes")
class CatalogQueryBusquedaCortaTest extends PostgresTestBase {

    @Test
    @DisplayName("a 1–2 character term is not answered by the trigram indexes, even as a generic plan")
    void unTerminoCortoNoUsaElIndice() throws Exception {
        assertThat(planGenerico("ab")).doesNotContain("_trgm");
        assertThat(planGenerico("x")).doesNotContain("_trgm");
    }

    @Test
    @DisplayName("a term of 3+ characters is answered by both trigram indexes")
    void unTerminoLargoUsaLosIndices() throws Exception {
        assertThat(planGenerico("monky"))
                .contains("idx_productos_nombre_trgm")
                .contains("idx_productos_marca_trgm");
    }

    /** Plan of the repository's own predicate, forced generic: the planner cannot see the term's length. */
    private String planGenerico(String q) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL enable_seqscan = off");
                st.execute("SET LOCAL plan_cache_mode = force_generic_plan");
                String sql = "EXPLAIN SELECT count(*) FROM productos p WHERE "
                        + CatalogQueryRepository.predicadoQ(q);
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, q);
                    ps.setString(2, q);
                    StringBuilder plan = new StringBuilder();
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) plan.append(rs.getString(1)).append('\n');
                    }
                    return plan.toString();
                }
            } finally {
                c.rollback();
            }
        }
    }
}
