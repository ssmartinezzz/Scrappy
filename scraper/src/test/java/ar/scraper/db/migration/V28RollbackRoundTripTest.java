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
 * add-morashop-and-fix-entreno-pagination. Same contract as every other
 * {@code V*RollbackRoundTripTest}: applied migrations are byte-frozen, so the
 * rollback lives in {@code docs/DATABASE.md} and this test executes that exact
 * block against the real schema inside a transaction it always rolls back.
 *
 * <p>Order matters and is asserted: the {@code morashop} seed row is deleted
 * BEFORE the CHECK narrows back to 11 values. The other way round would leave,
 * for an instant, a CHECK narrower than data that still violates it.
 */
@Epic("Persistence")
@Feature("Site")
@Story("V28 rollback narrows the plataforma domain back to 11 values, seed-first")
@DisplayName("V28 migration — the documented rollback actually runs")
class V28RollbackRoundTripTest extends PostgresTestBase {

    @Test
    @DisplayName("Rolling back deletes the morashop seed row then narrows the CHECK to 11 values")
    void rollbackDeletesSeedThenNarrowsCheck() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                assertThat(checkDomain(st)).contains("morashop");
                assertThat(sitioKeys(st)).contains("morashop");

                st.execute(DocumentedRollback.sqlFor("V28"));

                // Vuelve al dominio de V27 — DOCE valores, con `inpro` — no al
                // de once de antes de V27. Un rollback que devolviera el
                // dominio anterior a V27 borraría `inpro` de abajo de una fila
                // viva, y fallaría contra ella.
                assertThat(checkDomain(st)).containsExactlyInAnyOrder(
                        "tiendanube", "shopify", "vtex", "vaypol", "woocommerce",
                        "monkyforce", "maximus", "fullh4rd", "compragamer",
                        "qloud", "oscommerce", "inpro");
                assertThat(sitioKeys(st))
                        .as("morashop tenia 0 productos (nunca scrapeado) -> el NOT EXISTS lo deja borrar")
                        .doesNotContain("morashop");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("La fila sembrada es un sitio de suplementos, como Entreno")
    void seedRowIsASupplementsSite() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT plataforma, rubro_forzado, origen, es_premium "
                             + "FROM sitio WHERE sitio_key = 'morashop'")) {
            assertThat(rs.next()).as("la fila de seed de morashop existe").isTrue();
            assertThat(rs.getString("plataforma")).isEqualTo("morashop");
            assertThat(rs.getString("rubro_forzado")).isEqualTo("suplementos");
            assertThat(rs.getString("origen")).isEqualTo("config");
            assertThat(rs.getBoolean("es_premium")).isFalse();
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
}
