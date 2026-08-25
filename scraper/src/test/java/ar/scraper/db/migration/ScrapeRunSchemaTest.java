package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * scrape-run-persistence-and-resume, slice 1 (task 1.1) — the invariants
 * {@code V29} encodes in the schema rather than in {@code ScraperService}.
 *
 * <p>These are DB-level because a run row outlives the process that wrote it.
 * Interruption detection at boot works by finding a row left {@code RUNNING}
 * with no {@code finished_at}; if application code could ever write a
 * {@code COMPLETED} row that still has a null {@code finished_at}, or a
 * {@code RUNNING} row that has one, that signal stops meaning anything. The
 * paired CHECK makes the two columns impossible to disagree.</p>
 *
 * <p><b>Every negative case asserts the exact SQLState, never a bare
 * {@code SQLException}.</b> An insert into a table that does not exist yet
 * throws {@code SQLException} too, so the looser assertion goes green
 * <i>before</i> the migration is written — a test that passes for the wrong
 * reason, which is worse than no test at all.</p>
 *
 * <p><b>Why {@code sitio_key} and not {@code sitio}</b>: {@code V23}'s header
 * records this repo already making the opposite choice and paying for it —
 * {@code productos.sitio -> sitio(nombre)} was specified, implemented, and
 * broke 28 tests with {@code Key (sitio)=(VCP) is not present in table
 * "sitio"}, because {@code V18} seeded that site as {@code 'Vcp'} while the
 * scraper writes {@code 'VCP'}. {@code sitio.nombre} is a display label;
 * {@code sitio.sitio_key} is the identity. This table stores identity, and
 * the value comes from {@code buildSiteList}, which yields config keys
 * ({@code freres}, {@code vcp}) — the key form, not the display form.</p>
 */
@Epic("Persistence")
@Feature("Scrape run tracking")
@Story("V29 — run and per-site rows, paired status/finished_at CHECK, site identity FK")
@DisplayName("V29 migration — scrape_run and scrape_run_site constraints")
class ScrapeRunSchemaTest extends PostgresTestBase {

    private static final String CHECK_VIOLATION  = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FK_VIOLATION     = "23503";

    /** Seeded by V18 and never truncated — site rows are vocabulary, not test residue. */
    private static final String SITIO_KEY = "freres";

    // ── scrape_run · the closed status vocabulary ────────────────────────────

    @Test
    @DisplayName("scrape_run.status accepts the five states the lifecycle can reach")
    void statusAcceptsTheClosedVocabulary() {
        for (String estado : new String[]{"COMPLETED", "CANCELLED", "INTERRUPTED", "ERROR"}) {
            assertThatCode(() -> insertarRun(estado, true))
                    .as("%s is a terminal state the lifecycle writes", estado)
                    .doesNotThrowAnyException();
        }
        assertThatCode(() -> insertarRun("RUNNING", false))
                .as("RUNNING is the only non-terminal state")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("scrape_run.status rejects a state outside the closed vocabulary")
    void statusRejectsAnInventedState() {
        assertThatThrownBy(() -> insertarRun("PAUSED", true))
                .as("a sixth state would be invisible to interruption detection")
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    // ── scrape_run · RUNNING and finished_at cannot disagree ─────────────────

    @Test
    @DisplayName("a RUNNING run with a finished_at is rejected")
    void runningCannotBeFinished() {
        assertThatThrownBy(() -> insertarRun("RUNNING", true))
                .as("boot-time detection looks for RUNNING + finished_at IS NULL; "
                    + "a RUNNING row that carries a finish time would hide from it")
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("a terminal run with no finished_at is rejected")
    void terminalCannotBeUnfinished() {
        assertThatThrownBy(() -> insertarRun("COMPLETED", false))
                .as("the mirror case: a COMPLETED row with no finish time would be "
                    + "read as interrupted on the next boot, forever")
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    // ── scrape_run · identity and provenance ─────────────────────────────────

    @Test
    @DisplayName("scrape_uuid is unique")
    void scrapeUuidIsUnique() throws Exception {
        String uuid = "11111111-1111-1111-1111-111111111111";
        ejecutar(sqlRun(uuid, "COMPLETED", true, "NULL", "NULL"));

        assertThatThrownBy(() -> ejecutar(sqlRun(uuid, "COMPLETED", true, "NULL", "NULL")))
                .as("the uuid is how a caller addresses a run across processes")
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("both provenance FKs are nullable — for opposite reasons")
    void provenanceIsOptionalOnBothSides() {
        // A cron run has no human; a manual run has no job. Requiring either
        // would make one of the two ways of starting a scrape unrepresentable.
        assertThatCode(() -> insertarRun("COMPLETED", true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("triggered_by rejects a user id that does not exist")
    void triggeredByIsAForeignKey() {
        String fantasma = "22222222-2222-2222-2222-222222222222";
        assertThatThrownBy(() -> ejecutar(sqlRun(
                "33333333-3333-3333-3333-333333333333", "COMPLETED", true,
                "'" + fantasma + "'::uuid", "NULL")))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(FK_VIOLATION);
    }

    @Test
    @DisplayName("cron_job_id rejects a job id that does not exist")
    void cronJobIdIsAForeignKey() {
        assertThatThrownBy(() -> ejecutar(sqlRun(
                "44444444-4444-4444-4444-444444444444", "COMPLETED", true,
                "NULL", "987654321")))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(FK_VIOLATION);
    }

    @Test
    @DisplayName("elapsed_time is NOT a column — it is derived from the two timestamps")
    void elapsedTimeIsNotStored() throws Exception {
        // The positive half is not decoration: `doesNotContain` on a table that
        // does not exist yet returns an empty list and passes, so on its own this
        // would go green before the migration — the exact failure mode this
        // file's negative cases assert SQLStates to avoid.
        assertThat(columnas("scrape_run"))
                .as("the run row exists and carries the two timestamps")
                .contains("started_at", "finished_at")
                .as("storing a duration alongside the two timestamps it is computed "
                    + "from is a 3FN violation and a second thing to keep in sync")
                .doesNotContain("elapsed_time");
    }

    @Test
    @DisplayName("started_at DESC is indexed — boot-time detection reads the newest run")
    void startedAtIsIndexed() throws Exception {
        assertThat(indices("scrape_run"))
                .as("V29 ships idx_scrape_run_started")
                .contains("idx_scrape_run_started");
    }

    // ── scrape_run_site · identity, vocabulary, cascade ──────────────────────

    @Test
    @DisplayName("scrape_run_site.status accepts the five per-site states")
    void siteStatusAcceptsTheClosedVocabulary() throws Exception {
        long runId = crearRun("55555555-5555-5555-5555-555555555555");
        String[] estados = {"PENDING", "RUNNING", "DONE", "ERROR", "SKIPPED"};
        for (int i = 0; i < estados.length; i++) {
            String sitioKey = "sitiodeprueba" + i;
            sembrarSitio(sitioKey);
            String estado = estados[i];
            assertThatCode(() -> ejecutar(sqlRunSite(runId, sitioKey, estado)))
                    .as("%s is a per-site state the lifecycle writes", estado)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("scrape_run_site.status rejects a state outside the closed vocabulary")
    void siteStatusRejectsAnInventedState() throws Exception {
        long runId = crearRun("66666666-6666-6666-6666-666666666666");

        assertThatThrownBy(() -> ejecutar(sqlRunSite(runId, SITIO_KEY, "RETRYING")))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    @DisplayName("sitio_key references sitio(sitio_key), the identity — not the display name")
    void siteKeyIsAForeignKeyOnIdentity() throws Exception {
        long runId = crearRun("77777777-7777-7777-7777-777777777777");

        assertThatThrownBy(() -> ejecutar(sqlRunSite(runId, "sitioquenoexiste", "PENDING")))
                .as("an unknown site must not silently become a run row")
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(FK_VIOLATION);
    }

    @Test
    @DisplayName("the display name is NOT accepted where the key is expected")
    void displayNameIsNotTheKey() throws Exception {
        long runId = crearRun("88888888-8888-8888-8888-888888888888");

        // V18 seeds ('Freres','freres'). This is the exact confusion that broke
        // 28 tests in V23; the FK must reject the display spelling outright
        // rather than let two vocabularies coexist in one column.
        assertThatThrownBy(() -> ejecutar(sqlRunSite(runId, "Freres", "PENDING")))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(FK_VIOLATION);
    }

    @Test
    @DisplayName("one site appears at most once per run")
    void siteIsUniquePerRun() throws Exception {
        long runId = crearRun("99999999-9999-9999-9999-999999999999");
        ejecutar(sqlRunSite(runId, SITIO_KEY, "PENDING"));

        assertThatThrownBy(() -> ejecutar(sqlRunSite(runId, SITIO_KEY, "DONE")))
                .as("a second row for the same site would make 'which sites are "
                    + "still pending' ambiguous, which is what resume reads")
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    @DisplayName("deleting a run takes its site rows with it")
    void siteRowsCascadeWithTheRun() throws Exception {
        long runId = crearRun("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        ejecutar(sqlRunSite(runId, SITIO_KEY, "DONE"));

        ejecutar("DELETE FROM scrape_run WHERE id = " + runId);

        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            assertThat(contar(st, "scrape_run_site"))
                    .as("orphan site rows would be counted by a later resume")
                    .isZero();
        }
    }

    @Test
    @DisplayName("a site row for a run that does not exist is rejected")
    void siteRowNeedsItsRun() {
        assertThatThrownBy(() -> ejecutar(sqlRunSite(987654321L, SITIO_KEY, "PENDING")))
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(FK_VIOLATION);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String sqlRun(String uuid, String status, boolean finished,
                          String triggeredBy, String cronJobId) {
        return """
            INSERT INTO scrape_run
                (scrape_uuid, started_at, finished_at, triggered_by, cron_job_id, status)
            VALUES ('%s'::uuid, now(), %s, %s, %s, '%s')
            """.formatted(uuid, finished ? "now()" : "NULL", triggeredBy, cronJobId, status);
    }

    private void insertarRun(String status, boolean finished) throws Exception {
        ejecutar(sqlRun(java.util.UUID.randomUUID().toString(), status, finished, "NULL", "NULL"));
    }

    private long crearRun(String uuid) throws Exception {
        ejecutar(sqlRun(uuid, "RUNNING", false, "NULL", "NULL"));
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id FROM scrape_run WHERE scrape_uuid = '" + uuid + "'::uuid")) {
            assertThat(rs.next()).isTrue();
            return rs.getLong(1);
        }
    }

    private String sqlRunSite(long runId, String sitioKey, String status) {
        return """
            INSERT INTO scrape_run_site (scrape_run_id, sitio_key, status)
            VALUES (%d, '%s', '%s')
            """.formatted(runId, sitioKey, status);
    }

    /** V18 seeds the configured sites; a test that needs another one adds it. */
    private void sembrarSitio(String sitioKey) throws Exception {
        ejecutar("""
            INSERT INTO sitio (nombre, sitio_key, plataforma, origen)
            VALUES ('%s', '%s', 'tiendanube', 'historico')
            ON CONFLICT DO NOTHING
            """.formatted(sitioKey, sitioKey));
    }

    private void ejecutar(String sql) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private java.util.List<String> columnas(String tabla) throws Exception {
        return consultarColumna("""
            SELECT column_name FROM information_schema.columns
            WHERE table_name = '%s'
            """.formatted(tabla));
    }

    private java.util.List<String> indices(String tabla) throws Exception {
        return consultarColumna(
                "SELECT indexname FROM pg_indexes WHERE tablename = '" + tabla + "'");
    }

    private java.util.List<String> consultarColumna(String sql) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            java.util.List<String> valores = new java.util.ArrayList<>();
            while (rs.next()) {
                valores.add(rs.getString(1));
            }
            return valores;
        }
    }

    private static int contar(Statement st, String tabla) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + tabla)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }
}
