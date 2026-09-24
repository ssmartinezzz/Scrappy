package ar.scraper.web;

import ar.scraper.aggregator.ResultAggregator;
import ar.scraper.aggregator.ResultAggregator.AggregatedResult;
import ar.scraper.config.ScraperConfig;
import ar.scraper.db.DatabaseService;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import ar.scraper.scrape.ScraperStatus;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exit the interrupted-run offer never had: until this it could only be
 * cleared by RESUMING, i.e. running a scrape nobody asked for.
 */
@Epic("Scraping")
@Feature("Resume")
@Story("Discarding an interrupted run closes it without scraping")
@DisplayName("ScraperService — descartar una corrida interrumpida")
class ScrapeRunDiscardTest extends PostgresTestBase {

    private DatabaseService db;
    private ScraperService service;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
        ResultAggregator aggregator = Mockito.mock(ResultAggregator.class);
        Mockito.when(aggregator.fromDB(Mockito.anyList()))
                .thenReturn(new AggregatedResult(List.of(), Map.of(), Map.of(),
                        ResultAggregator.calcularFacets(List.of()), 0, 0));
        service = new ScraperService(Mockito.mock(ScraperConfig.class), aggregator,
                db.scrapeRun(), db.sitios(), db.mlOutput(), db.siteRegistry(),
                Mockito.mock(ar.scraper.catalog.ProductPort.class),
                Mockito.mock(ar.scraper.pcs.TechSpecsIndexer.class));
    }

    @Test
    @DisplayName("closes the run as CANCELLED, clears the flag, and starts nothing")
    void descartaSinScrapear() throws Exception {
        long runId = db.crearScrapeRun(UUID.randomUUID(), Instant.now(), null, null,
                List.of("freres", "midway"));
        service.cargarDesdeBD();
        assertThat(service.getInterrumpida()).isNotNull();

        assertThat(service.descartarInterrumpidas()).isEqualTo(1);

        assertThat(service.getInterrumpida())
                .as("the offer is gone from memory")
                .isNull();
        assertThat(estado(runId)).isEqualTo("CANCELLED");
        assertThat(service.getStatus())
                .as("discarding never launches a scrape")
                .isNotEqualTo(ScraperStatus.RUNNING);
        assertThat(sitiosEnEstado(runId, "PENDING"))
                .as("a closed run may not leave sites claiming to be owed")
                .isZero();
    }

    @Test
    @DisplayName("closes EVERY interrupted run, not just the one being offered")
    void descartaTodasLasInterrumpidas() throws Exception {
        Instant vieja = Instant.now().minus(2, ChronoUnit.DAYS);
        long antigua = db.crearScrapeRun(UUID.randomUUID(), vieja, null, null, List.of("freres"));
        long reciente = db.crearScrapeRun(UUID.randomUUID(), Instant.now(), null, null, List.of("midway"));

        service.cargarDesdeBD();
        assertThat(service.getInterrumpida().runId())
                .as("ultimaInterrumpida() names only the newest")
                .isEqualTo(reciente);

        assertThat(service.descartarInterrumpidas()).isEqualTo(2);
        assertThat(estado(antigua)).isEqualTo("CANCELLED");
        assertThat(estado(reciente)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("keeps the finished_at the boot sweep already recorded")
    void noPisaElFinishedAtDeLaDeteccion() throws Exception {
        long runId = db.crearScrapeRun(UUID.randomUUID(), Instant.now(), null, null, List.of("freres"));
        service.cargarDesdeBD();
        Instant detectado = finishedAt(runId);

        service.descartarInterrumpidas();

        assertThat(finishedAt(runId))
                .as("a run that ended days ago does not end now")
                .isEqualTo(detectado);
    }

    @Test
    @DisplayName("no interrupted run: reports zero and changes nothing")
    void sinNadaQueDescartar() {
        assertThat(service.descartarInterrumpidas()).isZero();
    }

    private String estado(long runId) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT status FROM scrape_run WHERE id = " + runId)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private Instant finishedAt(long runId) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT finished_at FROM scrape_run WHERE id = " + runId)) {
            return rs.next() ? rs.getObject(1, java.time.OffsetDateTime.class).toInstant() : null;
        }
    }

    private int sitiosEnEstado(long runId, String estado) throws Exception {
        try (Connection c = dataSource().getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM scrape_run_site WHERE scrape_run_id = " + runId
                     + " AND status = '" + estado + "'")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
