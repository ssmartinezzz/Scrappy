package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import com.zaxxer.hikari.HikariDataSource;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scrape-run-persistence-and-resume, slice 1 (tasks 1.9-1.11) — design D1's
 * measurement, which ships instead of an index.
 *
 * <p>Slice 4 will bound catalog reads with {@code touched_at < started_at}.
 * {@code productos.touched_at} is unindexed, and the obvious reflex is to index
 * it. D1 argues against: {@code sp_upsert_run} bumps {@code touched_at} on every
 * row of every run even when nothing changed, so an index costs ~20k updates ×
 * 26 sites of write-path maintenance; and the predicate is <b>low-selectivity by
 * construction</b> — it keeps most of the table and excludes only what the
 * current run re-touched — riding a scan that already happens under
 * {@code activo} plus eighteen filters and an {@code ORDER BY}/{@code LIMIT}.</p>
 *
 * <p>That is an argument, not a number, so this measures three arms rather than
 * asserting the conclusion. <b>The rule for adding the index is deliberately
 * conjunctive</b>: the bound must be more than 10% slower, AND the index must
 * recover it, AND the planner must actually choose it — read off the
 * {@code EXPLAIN}, never assumed. A btree the planner declines is pure
 * write-path tax.</p>
 *
 * <p><b>The pool is not optional.</b> This runs against
 * {@link PostgresTestBase#pooledDataSource()} because the unpooled test
 * datasource opens a connection per call and inflates DB timings ~31x in this
 * repo's own recorded measurement. A number taken without it measures connection
 * setup and can invert the conclusion.</p>
 *
 * <p>This test asserts the <b>soundness of the harness</b> and a non-pathological
 * bound, not a performance threshold: a wall-clock assertion on shared CI
 * hardware is a flake generator. The three numbers are logged and recorded in
 * {@code docs/DATABASE.md} regardless of which way they come out.</p>
 */
@Epic("Persistence")
@Feature("Reader isolation")
@Story("D1 — measure the touched_at bound before indexing it")
@DisplayName("touched_at bound — three-arm benchmark (D1)")
class ScrapeRunIndexBenchmarkTest extends PostgresTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(ScrapeRunIndexBenchmarkTest.class);

    private static final int FILAS      = 20_000;
    private static final int PCT_TOCADO = 60;
    private static final int WARMUP     = 50;
    private static final int MEDIDAS    = 200;

    /** The bound: rows touched at or after this belong to the in-flight run. */
    private static final Instant CORTE = Instant.parse("2026-08-25T12:00:00Z");

    private HikariDataSource pool;

    @BeforeEach
    void abrirPool() {
        pool = pooledDataSource();
    }

    @AfterEach
    void cerrarPool() {
        if (pool != null) pool.close();
    }

    @Test
    @DisplayName("three arms: unbounded, bounded unindexed, bounded indexed")
    void medirLasTresRamas() throws Exception {
        sembrar();

        assertThat(contar("touched_at >= ?", CORTE))
                .as("the seeded distribution is the point: a bound that excludes "
                    + "almost nothing measures nothing")
                .isBetween((int) (FILAS * 0.5), (int) (FILAS * 0.7));

        double sinCota    = medir(SQL_SIN_COTA, false);
        double conCota    = medir(SQL_CON_COTA, true);

        crearIndice();
        double conIndice  = medir(SQL_CON_COTA, true);
        String plan       = explicar();
        boolean planUsaIndice = plan.contains("idx_prod_touched_at");
        borrarIndice();

        double sobrecostoPct = ((conCota - sinCota) / sinCota) * 100.0;
        boolean elIndiceRecupera = conIndice < conCota;

        LOG.info("""

                ┌─ D1 · cota touched_at ─ {} filas, {}% tocadas, {} warmup + {} medidas ─┐
                │ sin cota            p50 = {} ms
                │ con cota, sin índice p50 = {} ms   ({}% vs. sin cota)
                │ con cota, con índice p50 = {} ms   (¿recupera? {})
                │ ¿el planner elige el índice? {}
                └─ decisión: {} ─┘
                """,
                FILAS, PCT_TOCADO, WARMUP, MEDIDAS,
                fmt(sinCota), fmt(conCota), fmt(sobrecostoPct), fmt(conIndice),
                elIndiceRecupera, planUsaIndice,
                decision(sobrecostoPct, elIndiceRecupera, planUsaIndice));
        LOG.info("EXPLAIN (ANALYZE, BUFFERS) con índice presente:\n{}", plan);

        // Not a threshold on wall-clock — that flakes on shared hardware. This
        // catches the shape of a genuine regression: a bound that turns a scan
        // the query already does into something categorically worse.
        assertThat(conCota)
                .as("the bound rides an existing scan; if it ever costs 3x, D1's "
                    + "premise is wrong and the decision must be revisited")
                .isLessThan(sinCota * 3);

        // The decision that shipped. If reality moves, this goes red and somebody
        // re-reads the numbers instead of inheriting a stale conclusion.
        assertThat(sobrecostoPct > 10.0 && elIndiceRecupera && planUsaIndice)
                .as("V29 ships WITHOUT an index on touched_at. All three conditions "
                    + "must hold to justify one; measured: +%s%%, recovers=%s, "
                    + "planner picks it=%s", fmt(sobrecostoPct), elIndiceRecupera, planUsaIndice)
                .isFalse();
    }

    // ── el trabajo ───────────────────────────────────────────────────────────

    private static final String SQL_SIN_COTA = """
        SELECT url, nombre, precio FROM productos
         WHERE activo AND precio BETWEEN 1000 AND 400000
         ORDER BY precio DESC LIMIT 60
        """;

    private static final String SQL_CON_COTA = """
        SELECT url, nombre, precio FROM productos
         WHERE activo AND precio BETWEEN 1000 AND 400000 AND touched_at < ?
         ORDER BY precio DESC LIMIT 60
        """;

    private double medir(String sql, boolean conCota) throws Exception {
        for (int i = 0; i < WARMUP; i++) correr(sql, conCota);

        List<Long> nanos = new ArrayList<>(MEDIDAS);
        for (int i = 0; i < MEDIDAS; i++) {
            long t0 = System.nanoTime();
            correr(sql, conCota);
            nanos.add(System.nanoTime() - t0);
        }
        nanos.sort(null);
        long p50 = nanos.get(nanos.size() / 2);
        long p95 = nanos.get((int) (nanos.size() * 0.95));
        LOG.debug("  p50={} ms  p95={} ms", fmt(p50 / 1e6), fmt(p95 / 1e6));
        return p50 / 1e6;
    }

    private void correr(String sql, boolean conCota) throws Exception {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (conCota) ps.setObject(1, CORTE.atOffset(ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rs.getString(1);
            }
        }
    }

    private String explicar() throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "EXPLAIN (ANALYZE, BUFFERS) " + SQL_CON_COTA)) {
            ps.setObject(1, CORTE.atOffset(ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) sb.append(rs.getString(1)).append('\n');
            }
        }
        return sb.toString();
    }

    private void sembrar() throws Exception {
        try (Connection c = pool.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO productos (url, sitio, nombre, precio, activo, touched_at, created_at)
                    VALUES (?, 'Freres', ?, ?, true, ?, ?)
                    """)) {
                OffsetDateTime viejo  = CORTE.minusSeconds(3600).atOffset(ZoneOffset.UTC);
                OffsetDateTime nuevo  = CORTE.plusSeconds(60).atOffset(ZoneOffset.UTC);
                for (int i = 0; i < FILAS; i++) {
                    OffsetDateTime touched = (i % 100) < PCT_TOCADO ? nuevo : viejo;
                    ps.setString(1, "https://bench.test/p" + i);
                    ps.setString(2, "producto de benchmark " + i);
                    ps.setDouble(3, 1000 + (i % 390_000));
                    ps.setObject(4, touched);
                    ps.setObject(5, touched);
                    ps.addBatch();
                    if (i % 2000 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            try (Statement st = c.createStatement()) {
                st.execute("ANALYZE productos");
            }
        }
    }

    private void crearIndice() throws Exception {
        ejecutar("CREATE INDEX idx_prod_touched_at ON productos (touched_at)");
        ejecutar("ANALYZE productos");
    }

    private void borrarIndice() throws Exception {
        ejecutar("DROP INDEX IF EXISTS idx_prod_touched_at");
    }

    private void ejecutar(String sql) throws Exception {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private int contar(String where, Instant valor) throws Exception {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM productos WHERE " + where)) {
            ps.setObject(1, valor.atOffset(ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private static String decision(double sobrecostoPct, boolean recupera, boolean planner) {
        return (sobrecostoPct > 10.0 && recupera && planner)
                ? "AGREGAR el índice — las tres condiciones se cumplen"
                : "SIN índice — no se cumplen las tres condiciones";
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }
}
