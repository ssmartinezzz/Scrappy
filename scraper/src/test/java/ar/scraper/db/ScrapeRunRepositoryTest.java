package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scrape-run-persistence-and-resume, slice 1 (tasks 1.5-1.6).
 *
 * <p>The run row is the anchor for four later slices: its {@code started_at}
 * is the reader-isolation bound, its site rows are the authoritative site set,
 * and a row left RUNNING with no {@code finished_at} is the interruption
 * signal. These tests pin the parts the rest of the change reads.</p>
 *
 * <p><b>The clock test is the load-bearing one.</b> {@code productos.touched_at}
 * is written from {@code LocalDateTime.now().format("yyyy-MM-dd HH:mm:ss")}
 * ({@code ProductRepository:44}/{@code :71}), so every value in the column has
 * a zero fractional part even though the column holds microseconds. If
 * {@code started_at} kept sub-second precision, {@code touched_at >=
 * started_at} would silently exclude every row touched during the run's own
 * first second — and the soft-delete union built from that predicate would
 * then treat those products as absent and delete them. So the repository
 * truncates, and no caller can opt out.</p>
 */
@Epic("Persistence")
@Feature("Scrape run tracking")
@Story("ScrapeRunRepository — create, per-site progress, finalize, boot-time interruption")
@DisplayName("ScrapeRunRepository")
class ScrapeRunRepositoryTest extends PostgresTestBase {

    /** Seeded by V18, never truncated. */
    private static final String SITIO_CONOCIDO = "freres";

    private ScrapeRunRepository repo() {
        return new ScrapeRunRepository(dataSource());
    }

    // ── the clock ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("started_at is truncated to the second, matching touched_at's resolution")
    void startedAtIsTruncatedToTheSecond() throws Exception {
        Instant conFraccion = Instant.parse("2026-08-25T10:00:00.500Z");

        long runId = repo().crear(UUID.randomUUID(), conFraccion, null, null,
                List.of(SITIO_CONOCIDO));

        assertThat(repo().startedAtDe(runId))
                .as("a caller cannot opt out of the truncation by passing a "
                    + "sub-second Instant — the whole predicate depends on it")
                .contains(conFraccion.truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("a product touched in the run's own first second is INSIDE the bound")
    void firstSecondIsInsideTheBound() throws Exception {
        Instant arranque = Instant.parse("2026-08-25T10:00:00.500Z");
        long runId = repo().crear(UUID.randomUUID(), arranque, null, null,
                List.of(SITIO_CONOCIDO));
        Instant startedAt = repo().startedAtDe(runId).orElseThrow();

        // Exactly what sp_upsert_run writes: the same second, zero fraction.
        sembrarProducto("https://ejemplo.test/p1", "2026-08-25 10:00:00");

        assertThat(urlsTocadasDesde(startedAt))
                .as("this row belongs to the run. Excluded from the union, the "
                    + "soft-delete would see it as absent and deactivate it — "
                    + "worse than the bug the union exists to fix.")
                .containsExactly("https://ejemplo.test/p1");
    }

    @Test
    @DisplayName("a product touched the second before the run is OUTSIDE the bound")
    void previousSecondIsOutsideTheBound() throws Exception {
        Instant arranque = Instant.parse("2026-08-25T10:00:00.500Z");
        long runId = repo().crear(UUID.randomUUID(), arranque, null, null,
                List.of(SITIO_CONOCIDO));
        Instant startedAt = repo().startedAtDe(runId).orElseThrow();

        sembrarProducto("https://ejemplo.test/viejo", "2026-08-25 09:59:59");

        assertThat(urlsTocadasDesde(startedAt))
                .as("the contrast that makes the previous test mean something: "
                    + "truncation must not widen the bound into the past")
                .isEmpty();
    }

    // ── creation ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("creating a run writes one PENDING site row per requested site")
    void crearWritesPendingSiteRows() throws Exception {
        long runId = repo().crear(UUID.randomUUID(), Instant.now(), null, null,
                List.of(SITIO_CONOCIDO, "midway", "batuk"));

        assertThat(sitiosConEstado(runId, "PENDING"))
                .containsExactlyInAnyOrder(SITIO_CONOCIDO, "midway", "batuk");
    }

    @Test
    @DisplayName("a run starts RUNNING with no finished_at")
    void crearStartsRunning() throws Exception {
        long runId = repo().crear(UUID.randomUUID(), Instant.now(), null, null,
                List.of(SITIO_CONOCIDO));

        assertThat(estadoDelRun(runId)).isEqualTo("RUNNING");
        assertThat(finishedAtDe(runId)).isNull();
    }

    @Test
    @DisplayName("a site never scraped before gets its sitio row created, not an FK violation")
    void crearGetsOrCreatesTheSiteRow() throws Exception {
        String nuevo = "tiendareciennacida";
        assertThat(existeSitio(nuevo)).isFalse();

        long runId = repo().crear(UUID.randomUUID(), Instant.now(), null, null, List.of(nuevo));

        // V23's FK only survives because sp_upsert_run seeds `sitio` before the
        // product that references it. These rows are written at run START,
        // before any scraping, so that seeding has not happened yet.
        assertThat(existeSitio(nuevo))
                .as("without a get-or-create here, a brand-new site makes the run "
                    + "fail to start at all")
                .isTrue();
        assertThat(sitiosConEstado(runId, "PENDING")).containsExactly(nuevo);
    }

    @Test
    @DisplayName("a site name with spaces and case is stored as its normalized key")
    void crearNormalizesTheSiteKeyInSql() throws Exception {
        long runId = repo().crear(UUID.randomUUID(), Instant.now(), null, null,
                List.of("Mi Tienda Nueva"));

        // Normalized by the SAME SQL expression sp_upsert_run uses, not by a
        // Java copy: SiteClassification.sitioKey() lowercases before filtering
        // and the SQL filters before lowercasing, which diverge under a Turkish
        // locale. One expression cannot disagree with itself.
        assertThat(sitiosConEstado(runId, "PENDING")).containsExactly("mitiendanueva");
    }

    // ── per-site progress ────────────────────────────────────────────────────

    @Test
    @DisplayName("a site moves PENDING to RUNNING to DONE, carrying its product count")
    void siteProgressIsRecorded() throws Exception {
        long runId = repo().crear(UUID.randomUUID(), Instant.now(), null, null,
                List.of(SITIO_CONOCIDO));

        repo().marcarSitioEnCurso(runId, SITIO_CONOCIDO, Instant.now());
        assertThat(sitiosConEstado(runId, "RUNNING")).containsExactly(SITIO_CONOCIDO);

        repo().marcarSitioTerminado(runId, SITIO_CONOCIDO, "DONE", 42, null, Instant.now());
        assertThat(sitiosConEstado(runId, "DONE")).containsExactly(SITIO_CONOCIDO);
        assertThat(productosDelSitio(runId, SITIO_CONOCIDO)).isEqualTo(42);
    }

    @Test
    @DisplayName("a failed site records ERROR and keeps its message")
    void siteFailureKeepsItsMessage() throws Exception {
        long runId = repo().crear(UUID.randomUUID(), Instant.now(), null, null,
                List.of(SITIO_CONOCIDO));

        repo().marcarSitioTerminado(runId, SITIO_CONOCIDO, "ERROR", 0,
                "timeout tras 600s", Instant.now());

        assertThat(sitiosConEstado(runId, "ERROR")).containsExactly(SITIO_CONOCIDO);
        assertThat(errorDelSitio(runId, SITIO_CONOCIDO)).isEqualTo("timeout tras 600s");
    }

    // ── finalization ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("finalizing sets the terminal status, the finish time and the count together")
    void finalizarClosesTheRun() throws Exception {
        long runId = repo().crear(UUID.randomUUID(), Instant.now(), null, null,
                List.of(SITIO_CONOCIDO));

        repo().finalizar(runId, "COMPLETED", 1234, Instant.now());

        assertThat(estadoDelRun(runId)).isEqualTo("COMPLETED");
        assertThat(finishedAtDe(runId))
                .as("the paired CHECK rejects a terminal status with a null finish "
                    + "time, so these two can never be written apart")
                .isNotNull();
        assertThat(productosDelRun(runId)).isEqualTo(1234);
    }

    // ── boot-time interruption ───────────────────────────────────────────────

    @Test
    @DisplayName("a run left RUNNING by a dead process is marked INTERRUPTED at boot")
    void bootMarksTheAbandonedRun() throws Exception {
        long abandonado = repo().crear(UUID.randomUUID(), Instant.now(), null, null,
                List.of(SITIO_CONOCIDO));

        List<Long> marcados = repo().marcarInterrumpidosAlArrancar(Instant.now());

        assertThat(marcados).containsExactly(abandonado);
        assertThat(estadoDelRun(abandonado)).isEqualTo("INTERRUPTED");
        assertThat(finishedAtDe(abandonado))
                .as("INTERRUPTED is terminal, so the paired CHECK requires a finish time")
                .isNotNull();
    }

    @Test
    @DisplayName("marking at boot is what stops a second restart seeing two live runs")
    void bootMarkingIsIdempotent() throws Exception {
        repo().crear(UUID.randomUUID(), Instant.now(), null, null, List.of(SITIO_CONOCIDO));

        repo().marcarInterrumpidosAlArrancar(Instant.now());
        List<Long> segundaVez = repo().marcarInterrumpidosAlArrancar(Instant.now());

        assertThat(segundaVez)
                .as("the first boot already closed it; a second must find nothing "
                    + "left RUNNING, or every restart re-reports the same run")
                .isEmpty();
    }

    @Test
    @DisplayName("a completed run is untouched at boot")
    void bootLeavesFinishedRunsAlone() throws Exception {
        long completado = repo().crear(UUID.randomUUID(), Instant.now(), null, null,
                List.of(SITIO_CONOCIDO));
        repo().finalizar(completado, "COMPLETED", 10, Instant.now());

        repo().marcarInterrumpidosAlArrancar(Instant.now());

        assertThat(estadoDelRun(completado)).isEqualTo("COMPLETED");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * The {@code +00} is not decoration. A zoneless literal cast to
     * {@code timestamptz} is interpreted in the SESSION timezone, which here is
     * -03 — so {@code '09:59:59'} would land at {@code 12:59:59Z} and sit
     * comfortably after a 10:00Z bound. Both bound tests passed that way before
     * this was pinned: green, and measuring nothing.
     */
    private void sembrarProducto(String url, String touchedAtUtc) throws Exception {
        ejecutar("""
            INSERT INTO productos (url, sitio, nombre, precio, activo, touched_at, created_at)
            VALUES ('%s', 'Freres', 'producto de prueba', 100, true,
                    '%s+00'::timestamptz, '%s+00'::timestamptz)
            """.formatted(url, touchedAtUtc, touchedAtUtc));
    }

    private List<String> urlsTocadasDesde(Instant desde) throws Exception {
        try (Connection c = dataSource().getConnection();
             var ps = c.prepareStatement(
                     "SELECT url FROM productos WHERE touched_at >= ? ORDER BY url")) {
            ps.setObject(1, desde.atOffset(java.time.ZoneOffset.UTC));
            try (ResultSet rs = ps.executeQuery()) {
                return leerColumna(rs);
            }
        }
    }

    private List<String> sitiosConEstado(long runId, String estado) throws Exception {
        return consultarColumna("""
            SELECT sitio_key FROM scrape_run_site
            WHERE scrape_run_id = %d AND status = '%s' ORDER BY sitio_key
            """.formatted(runId, estado));
    }

    private String estadoDelRun(long runId) throws Exception {
        return unString("SELECT status FROM scrape_run WHERE id = " + runId);
    }

    private String finishedAtDe(long runId) throws Exception {
        return unString("SELECT finished_at::text FROM scrape_run WHERE id = " + runId);
    }

    private int productosDelRun(long runId) throws Exception {
        return unInt("SELECT productos_count FROM scrape_run WHERE id = " + runId);
    }

    private int productosDelSitio(long runId, String sitioKey) throws Exception {
        return unInt("""
            SELECT productos_count FROM scrape_run_site
            WHERE scrape_run_id = %d AND sitio_key = '%s'
            """.formatted(runId, sitioKey));
    }

    private String errorDelSitio(long runId, String sitioKey) throws Exception {
        return unString("""
            SELECT error FROM scrape_run_site
            WHERE scrape_run_id = %d AND sitio_key = '%s'
            """.formatted(runId, sitioKey));
    }

    private boolean existeSitio(String sitioKey) throws Exception {
        return unString("SELECT sitio_key FROM sitio WHERE sitio_key = '" + sitioKey + "'") != null;
    }

    private void ejecutar(String sql) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private String unString(String sql) throws Exception {
        List<String> filas = consultarColumna(sql);
        return filas.isEmpty() ? null : filas.get(0);
    }

    private int unInt(String sql) throws Exception {
        String valor = unString(sql);
        return valor == null ? -1 : Integer.parseInt(valor);
    }

    private List<String> consultarColumna(String sql) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return leerColumna(rs);
        }
    }

    private static List<String> leerColumna(ResultSet rs) throws Exception {
        List<String> valores = new java.util.ArrayList<>();
        while (rs.next()) {
            valores.add(rs.getString(1));
        }
        return valores;
    }
}
