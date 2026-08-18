package ar.scraper.db.migration;

import ar.scraper.db.DatabaseService;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * normalize-db-schema-fks-1nf, slice B (V7) — design D5's rollback.
 *
 * <p>An applied Flyway migration is byte-frozen, so V7's rollback is
 * documented in {@code docs/DATABASE.md} instead of inside the {@code .sql}
 * (adding a single comment to a shipped migration breaks checksum validation
 * and the backend stops booting). A documented rollback nobody ever runs is a
 * guess, so this test reads that exact block from the doc — between its
 * {@code rollback:V7} markers — and executes it against the real migrated
 * schema inside a transaction that is always rolled back.</p>
 *
 * <p>What it proves is the property that makes the child tables the sole
 * source of truth with no expand/contract window: the round trip is LOSSLESS,
 * precisely because {@code posicion} exists. Without an ordinal, re-aggregating
 * would have to invent an order.</p>
 */
@Epic("Persistence")
@Feature("Multi-value normalization")
@Story("V7 rollback re-aggregates the child tables losslessly")
@DisplayName("V7 migration — the documented rollback actually runs, and is lossless")
class V7RollbackRoundTripTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    @Test
    @DisplayName("Rolling back rebuilds talles/ml_badge with their original order")
    void rollbackReaggregatesBothColumnsInOrder() throws Exception {
        db.upsertProductos(List.of(
                producto("https://site.com/rollback-a", List.of("S", "M", "L"),
                        List.of("verified_deal", "trending")),
                producto("https://site.com/rollback-vacio", List.of(), List.of())));

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(rollbackSql());

                assertThat(valor(st, "talles", "https://site.com/rollback-a"))
                        .isEqualTo("[\"S\", \"M\", \"L\"]");
                assertThat(valor(st, "ml_badge", "https://site.com/rollback-a"))
                        .isEqualTo("verified_deal,trending");

                assertThat(valor(st, "talles", "https://site.com/rollback-vacio")).isEqualTo("[]");
                assertThat(valor(st, "ml_badge", "https://site.com/rollback-vacio")).isEmpty();

                assertThat(tablaExiste(st, "producto_talle")).isFalse();
                assertThat(tablaExiste(st, "producto_badge")).isFalse();
            } finally {
                c.rollback();
            }
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private Product producto(String url, List<String> talles, List<String> badges) {
        Product.MlScore ml = new Product.MlScore(80, badges, true, "estable", 20, 0.5, "standard");
        return new Product("Sitio", "Producto", 1000.0, null, url, "http://img.example/x.jpg",
                "Remera", "unisex", talles, ml, "Nike", "indumentaria", false, false,
                Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY, 1);
    }

    private static String valor(Statement st, String columna, String url) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT " + columna + " FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).as("producto %s presente", url).isTrue();
            return rs.getString(1);
        }
    }

    private static boolean tablaExiste(Statement st, String tabla) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT to_regclass('public." + tabla + "') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    /** El bloque de rollback documentado en {@code docs/DATABASE.md}, verbatim. */
    private static String rollbackSql() {
        return DocumentedRollback.sqlFor("V7");
    }
}
