package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scrape-run-persistence-and-resume, slice 2 (design D4).
 *
 * <p>The soft-delete scope is derived from {@code touched_at >= run.started_at}
 * instead of from the batch handed to {@code upsertProductos}. On a resumed run
 * the batch only holds the resumed half, so the batch-derived scope silently
 * stops sweeping every site the crashed half had covered.</p>
 *
 * <p><b>Why the fixtures set {@code touched_at} explicitly.</b> Three separate
 * ways a test on this column can be green and prove nothing:</p>
 * <ol>
 *   <li><b>Second granularity.</b> {@code ProductRepository} writes
 *       {@code touched_at} through {@code "yyyy-MM-dd HH:mm:ss"}, so every
 *       stored value sits on a whole second. A run whose {@code started_at}
 *       carried sub-second precision would exclude everything touched during
 *       its own first second — which is why {@code ScrapeRunRepository.crear}
 *       truncates, and why the same-second case below is its own test.</li>
 *   <li><b>Literal timezone.</b> A bare {@code '2026-08-25 10:00:00'::timestamptz}
 *       is read in the <i>session</i> zone, which pgjdbc sets from the JVM — so
 *       the same literal means different instants under the test JVM and under
 *       {@code psql}. Every instant here is bound as a parameter at UTC, never
 *       written as a literal.</li>
 *   <li><b>Wall-clock proximity.</b> Seeding fixtures "now" and starting the run
 *       "now" lands both in the same second almost always, which would collapse
 *       the equivalence case and the same-second case into one another. The
 *       pre-existing rows are pinned a full 10 s before {@code started_at}.</li>
 * </ol>
 */
@Epic("Persistence")
@Feature("Scrape run persistence (scrape-run-persistence-and-resume)")
@Story("Soft-delete scope spans the whole run, not just the last batch")
@DisplayName("ProductRepository — soft-delete union derived from touched_at")
class ProductRepositorySoftDeleteUnionTest extends PostgresTestBase {

    private DatabaseService db;

    /** Whole seconds, because that is the only precision {@code touched_at} keeps. */
    private Instant runStart;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
        runStart = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    // ── Scenario: the change is a no-op on the common path ───────────────────

    @Test
    @DisplayName("A non-resumed run sweeps exactly what the batch-derived scope swept")
    void unionEquivaleAlAlcanceDerivadoDelBatch() throws Exception {
        // Two products on the same site, both left over from an earlier run.
        db.upsertProductos(List.of(
                productoDe("Uno", "https://uno.com/a", "A", 1000.0),
                productoDe("Uno", "https://uno.com/b", "B", 2000.0)));
        fijarTouchedAt("https://uno.com/a", runStart.minusSeconds(10));
        fijarTouchedAt("https://uno.com/b", runStart.minusSeconds(10));

        // This run re-scrapes site "Uno" and only finds A. B is genuinely gone.
        DatabaseService.UpsertStats stats = db.upsertProductos(
                List.of(productoDe("Uno", "https://uno.com/a", "A", 1000.0)), runStart);

        // Identical to today: B is on a site this run covered and was not seen.
        assertThat(stats.desactivados()).isEqualTo(1);
        assertThat(estaActivo("https://uno.com/a")).isTrue();
        assertThat(estaActivo("https://uno.com/b")).isFalse();
    }

    // ── Scenario: the correctness fix ────────────────────────────────────────

    @Test
    @DisplayName("A resumed run sweeps sites the crashed half covered, not just the resumed half")
    void unionAbarcaElSegmentoAnterior() throws Exception {
        // Stale row on site "Uno", left by an earlier run and never seen again.
        db.upsertProductos(List.of(productoDe("Uno", "https://uno.com/viejo", "Viejo", 500.0)));
        fijarTouchedAt("https://uno.com/viejo", runStart.minusSeconds(10));

        // First half of THIS run reached site "Uno" through upsertParcial, then crashed.
        db.upsertParcial(List.of(productoDe("Uno", "https://uno.com/nuevo", "Nuevo", 700.0)));
        fijarTouchedAt("https://uno.com/nuevo", runStart.plusSeconds(1));

        // Resume covers only site "Dos". The batch knows nothing about "Uno".
        DatabaseService.UpsertStats stats = db.upsertProductos(
                List.of(productoDe("Dos", "https://dos.com/x", "X", 900.0)), runStart);

        // The union spans both halves, so "Uno" is in scope and its stale row goes.
        // With the batch-derived scope this row survives — that is the bug.
        //
        // NEGATIVE CONTROL, worth stating because the obvious fix is worse than
        // the bug: widening only p_sitios to every site of the run, while p_urls
        // still held just this batch's URLs, would make EVERY product of site
        // "Uno" look absent — including "nuevo", which this very run had just
        // written. On the real 26-site catalogue that deactivates 24 sites
        // entirely. Both arrays have to come from the same query, which is why
        // alcanceDelRun returns them as one Alcance and never as two lookups.
        assertThat(estaActivo("https://uno.com/viejo")).isFalse();
        assertThat(estaActivo("https://uno.com/nuevo")).isTrue();
        assertThat(estaActivo("https://dos.com/x")).isTrue();
        assertThat(stats.desactivados()).isEqualTo(1);
    }

    // ── Scenario: the boundary the truncation exists to protect ──────────────

    @Test
    @DisplayName("A row touched in the run's own first second is inside the union")
    void filaDelMismoSegundoQuedaDentroDeLaUnion() throws Exception {
        // Both rows belong to site "Uno". The first is touched at exactly
        // started_at — the instant an inclusive, second-truncated bound must keep.
        db.upsertProductos(List.of(
                productoDe("Uno", "https://uno.com/borde", "Borde", 100.0),
                productoDe("Uno", "https://uno.com/otro", "Otro", 200.0)));
        fijarTouchedAt("https://uno.com/borde", runStart);
        fijarTouchedAt("https://uno.com/otro", runStart.minusSeconds(10));

        // The run re-scrapes "Uno" and finds only "otro".
        DatabaseService.UpsertStats stats = db.upsertProductos(
                List.of(productoDe("Uno", "https://uno.com/otro", "Otro", 200.0)), runStart);

        // "borde" was touched during this run's first second, so it is present,
        // not absent. An exclusive bound — or a sub-second started_at — would
        // drop it from p_urls and soft-delete a product this run had just seen.
        assertThat(estaActivo("https://uno.com/borde")).isTrue();
        assertThat(estaActivo("https://uno.com/otro")).isTrue();
        assertThat(stats.desactivados()).isZero();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Product productoDe(String sitio, String url, String nombre, double precio) {
        return new Product(
                sitio, nombre, precio, null, url, "http://img.example/x.jpg",
                "Remera", "unisex", List.of("M", "L"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }

    /**
     * Pins a row's {@code touched_at}. Bound as a parameter at UTC rather than
     * written as a SQL literal: a bare timestamp literal is interpreted in the
     * session zone, which pgjdbc takes from the JVM, so the literal form would
     * mean different instants depending on which machine ran the suite.
     */
    private void fijarTouchedAt(String url, Instant cuando) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE productos SET touched_at = ? WHERE url = ?")) {
            ps.setObject(1, cuando.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC));
            ps.setString(2, url);
            assertThat(ps.executeUpdate())
                    .as("fixture row %s must exist before its touched_at is pinned", url)
                    .isEqualTo(1);
        }
    }

    private boolean estaActivo(String url) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT activo FROM productos WHERE url = ?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("row %s must exist", url).isTrue();
                return rs.getBoolean(1);
            }
        }
    }
}
