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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * close-1nf-and-3nf-foundation, Phase 4 (V17, design DD7).
 *
 * <p>Same contract as {@code V7RollbackRoundTripTest}/{@code V8RollbackRoundTripTest}/
 * {@code V16RollbackRoundTripTest}: applied Flyway migrations are byte-frozen,
 * so V17's rollback lives in {@code docs/ARCHITECTURE.md} and this test
 * executes that exact block against the real schema inside a transaction it
 * always rolls back.</p>
 *
 * <p><b>NOT lossless</b>, and the doc must not imply otherwise: the messy
 * source strings ({@code "$ 1.249.999,99"}, {@code "ARS 45.000"}) are gone —
 * this yields canonical numeric strings instead. {@code sp_upsert_run} is
 * NOT restored to its V7 body by this block (Postgres has no assignment cast
 * from an already-numeric column back to a raw JSON string extraction, and
 * restoring the function is a separate forward migration, same caveat as
 * V7's own rollback note) — this test only pins the column-type half.</p>
 */
@Epic("Persistence")
@Feature("Price normalization")
@Story("V17 rollback returns precio_orig to TEXT")
@DisplayName("V17 migration — the documented rollback actually runs")
class V17RollbackRoundTripTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    @Test
    @DisplayName("Rolling back returns precio_orig to TEXT with a canonical numeric string")
    void rollbackReturnsPrecioOrigToText() throws Exception {
        String url = "https://site.com/v17-rollback";
        db.upsertProductos(List.of(new Product("Sitio", "Producto", 1000.0, 45000.0, url,
                "http://img.example/x.jpg", "Remera", "unisex", List.of(), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1)));

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(rollbackSql());

                assertThat(dataType(st, "productos", "precio_orig")).isEqualTo("text");

                String crudo = valor(st, "SELECT precio_orig FROM productos WHERE url = '" + url + "'");
                // FM suprime ceros de relleno (líderes Y finales) — Postgres
                // devuelve "45000.", no "45000.00". No lossless, dicho en la
                // prosa de arriba: no es el string crudo que un sitio escribió.
                assertThat(crudo).isEqualTo("45000.");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("Un producto sin precio original vuelve a NULL, no a un string vacío")
    void rollbackKeepsNullAsNull() throws Exception {
        String url = "https://site.com/v17-rollback-null";
        db.upsertProductos(List.of(new Product("Sitio", "Producto", 1000.0, null, url,
                "http://img.example/x.jpg", "Remera", "unisex", List.of(), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1)));

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(rollbackSql());

                try (ResultSet rs = st.executeQuery(
                        "SELECT precio_orig FROM productos WHERE url = '" + url + "'")) {
                    assertThat(rs.next()).isTrue();
                    rs.getString(1);
                    assertThat(rs.wasNull()).isTrue();
                }
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

    /** The rollback block documented in {@code docs/ARCHITECTURE.md}, verbatim. */
    private static String rollbackSql() {
        String doc = readArchitectureDoc();
        String open = "-- >>> rollback:V17";
        String close = "-- <<< rollback:V17";
        int start = doc.indexOf(open);
        assertThat(start).as("opening marker '%s' present in docs/ARCHITECTURE.md", open).isNotEqualTo(-1);
        int end = doc.indexOf(close, start);
        assertThat(end).as("closing marker '%s' present in docs/ARCHITECTURE.md", close).isNotEqualTo(-1);
        String sql = doc.substring(start + open.length(), end).trim();
        assertThat(sql).as("the documented rollback block is not empty").isNotEmpty();
        return sql;
    }

    private static String readArchitectureDoc() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("docs").resolve("ARCHITECTURE.md");
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("docs/ARCHITECTURE.md not found walking up from "
                + System.getProperty("user.dir"));
    }
}
