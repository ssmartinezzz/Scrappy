package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scrape-run-persistence-and-resume, slice 1 (tasks 1.7-1.8) — the half of the
 * lifecycle that runs without scraping anything.
 *
 * <p>The boot path is the piece worth isolating: it decides, once per process
 * start, whether the previous process died mid-run. It is also the piece with a
 * standing rule attached — it <b>only marks</b>, and must never start a scrape
 * on its own. A restart that silently resumed work nobody asked for would be a
 * far worse failure than the crash it is reacting to.</p>
 *
 * <p>{@code DatabaseService} is real, against real Postgres; only
 * {@code ScraperConfig} and {@code ResultAggregator} are mocked, because the
 * subject here is what lands in the two tables.</p>
 *
 * <p><b>Not covered here, and deliberately not faked</b>: "a run opens its row
 * and reaches COMPLETED with correct site rows" needs {@code ejecutarScraping}
 * to actually run, which means real browsers against live stores. Those
 * transitions are asserted directly in {@code ScrapeRunRepositoryTest}; the
 * wiring that calls them is verified against a real process, not here.</p>
 */
@Epic("Scraping")
@Feature("Scrape run tracking")
@Story("Boot-time interruption detection marks, and only marks")
@DisplayName("ScraperService — run lifecycle at boot")
class ScrapeRunLifecycleTest extends PostgresTestBase {

    private DatabaseService db;
    private ScraperService service;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
        service = new ScraperService(
                Mockito.mock(ScraperConfig.class),
                Mockito.mock(ResultAggregator.class),
                db);
    }

    @Test
    @DisplayName("a run the last process left open is marked INTERRUPTED at boot")
    void bootMarksTheAbandonedRun() throws Exception {
        long abandonado = db.crearScrapeRun(UUID.randomUUID(), Instant.now(), null, null,
                List.of("freres"));

        service.cargarDesdeBD();

        assertThat(estadoDelRun(abandonado)).isEqualTo("INTERRUPTED");
    }

    @Test
    @DisplayName("boot detection does NOT start a scrape")
    void bootDoesNotStartAnything() throws Exception {
        db.crearScrapeRun(UUID.randomUUID(), Instant.now(), null, null, List.of("freres"));

        service.cargarDesdeBD();

        assertThat(service.getRunState())
                .as("detection only marks. A restart that resumed work nobody asked "
                    + "for would be worse than the crash it reacts to.")
                .isNull();
        assertThat(service.getStatus())
                .as("no products restored and nothing launched, so the service stays idle")
                .isEqualTo(ScraperService.ScraperStatus.IDLE);
    }

    @Test
    @DisplayName("a finished run is left exactly as it was")
    void bootLeavesFinishedRunsAlone() throws Exception {
        long completado = db.crearScrapeRun(UUID.randomUUID(), Instant.now(), null, null,
                List.of("freres"));
        db.finalizarScrapeRun(completado, "COMPLETED", 10, Instant.now());

        service.cargarDesdeBD();

        assertThat(estadoDelRun(completado)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("a second restart finds nothing left open")
    void bootIsIdempotentAcrossRestarts() throws Exception {
        db.crearScrapeRun(UUID.randomUUID(), Instant.now(), null, null, List.of("freres"));

        service.cargarDesdeBD();
        service.cargarDesdeBD();

        // Two restarts, one interrupted run. Without the marking, the second boot
        // would find it still RUNNING and "the interrupted run" would stop naming
        // one thing the moment a third restart happened.
        assertThat(runsConEstado("RUNNING")).isZero();
        assertThat(runsConEstado("INTERRUPTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("boot survives a database that cannot answer — it never aborts startup")
    void bootToleratesADatabaseFailure() {
        DatabaseService roto = Mockito.mock(DatabaseService.class);
        ScraperService conDbRota = new ScraperService(
                Mockito.mock(ScraperConfig.class), Mockito.mock(ResultAggregator.class), roto);

        // Run bookkeeping is bookkeeping: it must not be able to stop the
        // application from starting. @PostConstruct throwing would do exactly that.
        org.assertj.core.api.Assertions.assertThatCode(conDbRota::cargarDesdeBD)
                .doesNotThrowAnyException();
    }

    private String estadoDelRun(long runId) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT status FROM scrape_run WHERE id = " + runId)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private int runsConEstado(String estado) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM scrape_run WHERE status = '" + estado + "'")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
