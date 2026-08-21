package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fix-zero-yield-tech-sites, C3 (design D4). Same contract as every other
 * {@code V*RollbackRoundTripTest}: applied migrations are byte-frozen, so the
 * rollback lives in {@code docs/DATABASE.md} and this test executes that
 * exact block against the real schema inside a transaction it always rolls
 * back.
 *
 * <p>Order matters and is asserted here: the seed rows for {@code qloud}/
 * {@code oscommerce} are deleted BEFORE the CHECK narrows back to 9 values —
 * doing it the other way would, for an instant, leave a CHECK narrower than
 * data that still violates it.</p>
 *
 * <p><b>add-morashop-and-fix-entreno-pagination, {@code CODE-2} declared</b>:
 * this test was edited by a later change that did not touch V24. V28 widens
 * the same CHECK and seeds a {@code morashop} row, so two things moved here.
 * The pre-state assertion goes from 11 values to 12, and V28's rollback now
 * runs BEFORE V24's — rollbacks compose in reverse order, and V24's block
 * cannot narrow past a row V28 planted. Nothing about V24's own behaviour
 * changed: the post-rollback assertion is still the same 9 values and the
 * seed-first ordering it exists to prove is untouched.
 *
 * <p>The rule this edit follows, so it does not erode into "edit whatever is
 * red": a CLOSED-DOMAIN assertion may move from {@code n} to {@code n+1} when
 * the domain legitimately grows, provided every behavioural assertion around
 * it stays exactly as it was. Softening a {@code containsExactlyInAnyOrder}
 * into a {@code contains} to make red go away would be the opposite.</p>
 */
@Epic("Persistence")
@Feature("Site")
@Story("V24 rollback narrows the plataforma domain back to 9 values, seed-first")
@DisplayName("V24 migration — the documented rollback actually runs")
class V24RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back deletes the qloud/oscommerce seed rows then narrows the CHECK to 9 values")
    void rollbackDeletesSeedThenNarrowsCheck() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                assertThat(checkDomain(st)).containsExactlyInAnyOrder(
                        "tiendanube", "shopify", "vtex", "vaypol", "woocommerce",
                        "monkyforce", "maximus", "fullh4rd", "compragamer",
                        "qloud", "oscommerce", "morashop");
                assertThat(sitioKeys(st)).contains("rockethard", "venex");

                // Rollbacks compose in reverse order: V28 widened this same
                // CHECK and seeded a morashop row, so V24's rollback cannot
                // narrow past it on its own.
                st.execute(DocumentedRollback.sqlFor("V28"));
                st.execute(rollbackSql());

                assertThat(checkDomain(st)).containsExactlyInAnyOrder(
                        "tiendanube", "shopify", "vtex", "vaypol", "woocommerce",
                        "monkyforce", "maximus", "fullh4rd", "compragamer");
                assertThat(sitioKeys(st))
                        .as("rockethard/venex tenian 0 productos (nunca scrapeados) -> el NOT EXISTS los deja borrar")
                        .doesNotContain("rockethard", "venex");
            } finally {
                c.rollback();
            }
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static java.util.List<String> checkDomain(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = 'sitio'::regclass AND conname = 'sitio_plataforma_check'")) {
            assertThat(rs.next()).as("sitio_plataforma_check existe").isTrue();
            String def = rs.getString(1);
            java.util.List<String> values = new java.util.ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([a-z0-9]+)'").matcher(def);
            while (m.find()) values.add(m.group(1));
            return values;
        }
    }

    private static java.util.List<String> sitioKeys(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT sitio_key FROM sitio")) {
            java.util.List<String> keys = new java.util.ArrayList<>();
            while (rs.next()) keys.add(rs.getString(1));
            return keys;
        }
    }

    /** El bloque de rollback documentado en {@code docs/DATABASE.md}, verbatim. */
    private static String rollbackSql() {
        return DocumentedRollback.sqlFor("V24");
    }
}
