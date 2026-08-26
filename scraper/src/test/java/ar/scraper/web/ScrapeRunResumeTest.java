package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * scrape-run-persistence-and-resume, slice 5 (tasks 5.2, 5.4) — detection is a
 * flag, and the resume the operator asks for.
 *
 * <p>Two rules are asserted here because breaking either is silent:</p>
 * <ul>
 *   <li><b>Detection never starts anything.</b> A restart that resumed work
 *       nobody asked for would be worse than the crash it reacts to — the server
 *       may have been restarted precisely to stop it scraping.</li>
 *   <li><b>A resume reuses the original run row.</b> Its {@code started_at} is
 *       the reader bound and the scope of the final sweep; a fresh row would make
 *       both name only the resumed half, and the sweep would then read the first
 *       half's products as absent and deactivate them.</li>
 * </ul>
 *
 * <p><b>Not exercised here, deliberately</b>: the path where sites are still
 * pending launches real browsers against live stores. Its database half is
 * asserted in {@code ScrapeRunResumeRepositoryTest} and the wiring is verified
 * against a real process. What runs here is the crash-during-aggregation case,
 * which owes only the final sweep and touches no network.</p>
 */
@Epic("Scraping")
@Feature("Resume")
@Story("Detection flags; resume reuses the original run")
@DisplayName("ScraperService — resuming an interrupted run")
class ScrapeRunResumeTest extends PostgresTestBase {

    private DatabaseService db;
    private ResultAggregator aggregator;
    private ScraperService service;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
        aggregator = Mockito.mock(ResultAggregator.class);
        Mockito.when(aggregator.fromDB(Mockito.anyList()))
                .thenReturn(new AggregatedResult(List.of(), Map.of(), Map.of(),
                        ResultAggregator.calcularFacets(List.of()), 0, 0));
        service = new ScraperService(Mockito.mock(ScraperConfig.class), aggregator, db);
    }

    @Test
    @DisplayName("boot loads the interrupted run as a flag, and starts nothing")
    void bootFlagsWithoutStarting() throws Exception {
        long runId = db.crearScrapeRun(UUID.randomUUID(), Instant.now(), null, null,
                List.of("freres", "midway"));
        db.marcarSitioTerminado(runId, "freres", "DONE", 10, null, Instant.now());

        service.cargarDesdeBD();

        var det = service.getInterrumpida();
        assertThat(det).isNotNull();
        assertThat(det.runId()).isEqualTo(runId);
        assertThat(det.atendidos()).containsExactly("freres");
        assertThat(det.pendientes()).containsExactly("midway");
        assertThat(service.getStatus())
                .as("detection offers; it does not act")
                .isEqualTo(ScraperService.ScraperStatus.IDLE);
    }

    @Test
    @DisplayName("with nothing interrupted there is nothing to resume")
    void nothingToResume() {
        service.cargarDesdeBD();

        assertThat(service.getInterrumpida()).isNull();
        assertThat(service.reanudar()).isFalse();
    }

    @Test
    @DisplayName("a crash during aggregation resumes as the final pass only, without re-scraping")
    void resumesAsFinalPassOnly() throws Exception {
        Instant arranque = Instant.parse("2026-08-25T10:00:00Z");
        long runId = db.crearScrapeRun(UUID.randomUUID(), arranque, null, null,
                List.of("freres", "midway"));
        db.marcarSitioTerminado(runId, "freres", "DONE", 10, null, Instant.now());
        db.marcarSitioTerminado(runId, "midway", "DONE", 20, null, Instant.now());
        service.cargarDesdeBD();

        assertThat(service.getInterrumpida().soloFaltaLaPasadaFinal())
                .as("every site finished; only the sweep is owed")
                .isTrue();

        assertThat(service.reanudar()).isTrue();
        esperarA(ScraperService.ScraperStatus.DONE);

        assertThat(estadoDelRun(runId))
                .as("the ORIGINAL row is closed, not a second one")
                .isEqualTo("COMPLETED");
        assertThat(cantidadDeRuns())
                .as("a resume that opened a new run would leave the bound naming "
                    + "only the resumed half")
                .isEqualTo(1);
        assertThat(startedAtDe(runId))
                .as("started_at survives the resume — that is what keeps the reader "
                    + "isolated from BOTH halves")
                .isEqualTo(arranque.truncatedTo(ChronoUnit.SECONDS));
        Mockito.verify(aggregator, Mockito.never())
                .agregar(Mockito.anyList(), Mockito.anyBoolean(), Mockito.any());
    }

    @Test
    @DisplayName("resuming twice does nothing the second time")
    void resumingIsNotRepeatable() throws Exception {
        long runId = db.crearScrapeRun(UUID.randomUUID(), Instant.now(), null, null,
                List.of("freres"));
        db.marcarSitioTerminado(runId, "freres", "DONE", 10, null, Instant.now());
        service.cargarDesdeBD();

        assertThat(service.reanudar()).isTrue();
        esperarA(ScraperService.ScraperStatus.DONE);

        assertThat(service.reanudar())
                .as("the offer is consumed; a second click must not reopen a closed run")
                .isFalse();
        assertThat(estadoDelRun(runId)).isEqualTo("COMPLETED");
    }

    /**
     * El agujero que ninguna de las dos ramas podía ver sola: el slice 4 cuelga
     * el aislamiento del lector de {@code abrirRun}, y una retoma no pasa por
     * ahí. Sin esto, reanudar sirve el catálogo a medio rearmar durante todo el
     * scrape — en el escenario donde más importa, porque una retoma corre sobre
     * un catálogo que ya quedó a medias.
     *
     * <p>Se observa DURANTE la corrida retomada, no después: al cerrarla el
     * aislamiento se suelta, así que un assert al final no distingue "aisló y
     * soltó" de "nunca aisló".</p>
     */
    @Test
    @DisplayName("a resumed run isolates the reader too, and on the ORIGINAL bound")
    void unaRetomaTambienAislaAlLector() throws Exception {
        // D6: la cota queda suprimida hasta que exista una corrida COMPLETED, o
        // una instalación nueva serviría una pantalla vacía. Con la cota
        // suprimida este test no probaría nada.
        long previa = db.crearScrapeRun(UUID.randomUUID(),
                Instant.parse("2026-08-24T10:00:00Z"), null, null, List.of("freres"));
        db.finalizarScrapeRun(previa, "COMPLETED", 1, Instant.parse("2026-08-24T11:00:00Z"));

        Instant arranque = Instant.parse("2026-08-25T10:00:00Z");
        long runId = db.crearScrapeRun(UUID.randomUUID(), arranque, null, null,
                List.of("freres"));
        db.marcarSitioTerminado(runId, "freres", "DONE", 10, null, Instant.now());
        service.cargarDesdeBD();

        AggregatedResult fotoPrevia = new AggregatedResult(List.of(), Map.of(), Map.of(),
                ResultAggregator.calcularFacets(List.of()), 0, 0);
        service.setLastResultParaTest(fotoPrevia);

        AggregatedResult rearmado = new AggregatedResult(List.of(), Map.of(), Map.of(),
                ResultAggregator.calcularFacets(List.of()), 0, 0);
        AtomicReference<Optional<Instant>> cotaDurante = new AtomicReference<>();
        AtomicReference<AggregatedResult> servidoDurante = new AtomicReference<>();
        Mockito.when(aggregator.fromDB(Mockito.anyList())).thenAnswer(inv -> {
            cotaDurante.set(service.cotaDeLectura());
            servidoDurante.set(service.getLastResult());
            return rearmado;
        });

        assertThat(service.reanudar()).isTrue();
        esperarA(ScraperService.ScraperStatus.DONE);

        assertThat(cotaDurante.get())
                .as("la cota es el started_at ORIGINAL, no uno nuevo: si no, nombraría "
                    + "sólo la mitad retomada")
                .contains(arranque.truncatedTo(ChronoUnit.SECONDS));
        assertThat(servidoDurante.get())
                .as("durante la retoma se sirve la foto previa, no el rearmado")
                .isSameAs(fotoPrevia);

        assertThat(service.cotaDeLectura())
                .as("cerrada la corrida el aislamiento se suelta")
                .isEmpty();
        assertThat(service.getLastResult()).isSameAs(rearmado);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * The resume runs on its own thread, so the assertions need it to have
     * landed. Polling rather than a fixed sleep: a sleep long enough to be safe
     * is a slow test, and one short enough to be fast is a flaky one.
     */
    private void esperarA(ScraperService.ScraperStatus esperado) throws Exception {
        long limite = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < limite) {
            if (service.getStatus() == esperado) return;
            Thread.sleep(25);
        }
        throw new AssertionError("el servicio nunca llegó a " + esperado
                + " (quedó en " + service.getStatus() + ": " + service.getStatusMsg() + ")");
    }

    private String estadoDelRun(long runId) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT status FROM scrape_run WHERE id = " + runId)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private Instant startedAtDe(long runId) throws Exception {
        return db.startedAtDeRun(runId).orElseThrow();
    }

    private int cantidadDeRuns() throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM scrape_run")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
