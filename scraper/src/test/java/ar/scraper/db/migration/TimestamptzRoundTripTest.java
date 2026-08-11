package ar.scraper.db.migration;

import ar.scraper.cron.CronExecution;
import ar.scraper.cron.CronJob;
import ar.scraper.db.DatabaseService;
import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * normalize-db-schema-fks-1nf, slice A.4 (V8).
 *
 * <p>What the API actually emits now that the columns are {@code TIMESTAMPTZ}.
 * The payload format IS a change, taken deliberately: pgjdbc's own
 * {@code getString} would render {@code 2026-08-11 17:15:00.936768-03} — the
 * server's timezone leaking into the API, in a shape {@code new Date(...)}
 * rejects. Every timestamp the backend hands out is UTC ISO-8601 to the
 * second, and it is stable regardless of the JVM's timezone.</p>
 */
@Epic("Persistence")
@Feature("Column domains")
@Story("Timestamps round-trip as UTC ISO-8601 — V8")
@DisplayName("V8 — timestamps round-trip as UTC ISO-8601")
class TimestamptzRoundTripTest extends PostgresTestBase {

    private static final String ISO_UTC = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$";
    private static final org.assertj.core.data.TemporalUnitOffset CINCO_MINUTOS =
            org.assertj.core.api.Assertions.within(5, ChronoUnit.MINUTES);

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    @Test
    @DisplayName("favoritos.added_at / last_checked_at come back as UTC ISO-8601")
    void favoritosTimestampsAreIsoUtc() {
        // favoritos.url is FK-bound to productos(url) since V4 — the product
        // has to exist before it can be favourited.
        db.upsertProductos(List.of(producto("https://site.com/fav-ts")));
        db.guardarFavorito("https://site.com/fav-ts", "Sitio", "Producto");
        db.tocarFavorito("https://site.com/fav-ts");

        Map<String, String> fav = db.listarFavoritos().get(0);

        assertThat(fav.get("added_at")).matches(ISO_UTC);
        assertThat(fav.get("last_checked_at")).matches(ISO_UTC);
        assertThat(Instant.parse(fav.get("added_at")))
                .isCloseTo(Instant.now(), CINCO_MINUTOS);
    }

    @Test
    @DisplayName("saved_outfits.created_at comes back as UTC ISO-8601")
    void savedOutfitCreatedAtIsIsoUtc() {
        db.guardarOutfit("Mi outfit", "[]", "[]", 1000.0);

        Map<String, Object> outfit = db.obtenerOutfitsGuardados().get(0);

        assertThat((String) outfit.get("createdAt")).matches(ISO_UTC);
    }

    @Test
    @DisplayName("A cron job's timestamps survive the round trip as the same instant")
    void cronJobTimestampsPreserveTheInstant() {
        String nextRunLocal = "2026-07-05T03:00:00";
        long id = db.insertCronJob("Job", 1000, 50000, List.of("Freres"),
                false, true, "0 0 3 * * *", true, nextRunLocal);

        CronJob job = db.getCronJob(id).orElseThrow();

        assertThat(job.createdAt()).matches(ISO_UTC);
        assertThat(job.updatedAt()).matches(ISO_UTC);
        assertThat(job.lastRunAt()).isNull();
        assertThat(job.nextRunAt()).matches(ISO_UTC);
        // The offset-less string CronJobService produces names a LOCAL time;
        // stored as timestamptz it keeps that instant, rendered in UTC.
        assertThat(Instant.parse(job.nextRunAt()))
                .isEqualTo(LocalDateTime.parse(nextRunLocal).atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    @DisplayName("A cron execution's started_at / finished_at round-trip, and finished_at stays null while running")
    void cronExecutionTimestampsRoundTrip() {
        long jobId = db.insertCronJob("Job", 1000, 50000, List.of("Freres"),
                false, true, "0 0 3 * * *", true, null);
        long execId = db.insertCronExecution(jobId, "2026-07-05T03:00:00", "running", null);

        CronExecution running = db.getExecution(execId).orElseThrow();
        assertThat(running.startedAt()).matches(ISO_UTC);
        assertThat(running.finishedAt()).isNull();

        db.updateCronExecution(execId, "2026-07-05T03:05:00", "success", null, "log", 300000);

        CronExecution finished = db.getExecution(execId).orElseThrow();
        assertThat(finished.finishedAt()).matches(ISO_UTC);
        assertThat(Instant.parse(finished.finishedAt()))
                .isEqualTo(LocalDateTime.parse("2026-07-05T03:05:00")
                        .atZone(ZoneId.systemDefault()).toInstant());
    }

    @Test
    @DisplayName("The human reclassification path stores bloqueado_at and applied_at as real instants")
    void lockAndAuditTimestampsAreRealInstants() throws Exception {
        String url = "https://site.com/ts-lock";
        db.upsertProductos(List.of(producto(url)));

        boolean ok = db.aplicarReclasificacionAuditada(url, "Buzos", "Nike", "hombre",
                List.of("M"), "Canguro", db.obtenerProducto(url).orElseThrow(), "santi");
        assertThat(ok).isTrue();

        assertThat(instante("SELECT bloqueado_at FROM productos WHERE url = '" + url + "'"))
                .isCloseTo(OffsetDateTime.now(), CINCO_MINUTOS);
        assertThat(instante("SELECT applied_at FROM agent_reclassify_audit WHERE url = '" + url + "'"))
                .isCloseTo(OffsetDateTime.now(), CINCO_MINUTOS);
    }

    private ar.scraper.model.Product producto(String url) {
        return new ar.scraper.model.Product("Sitio", "Producto", 1000.0, null, url,
                "http://img.example/x.jpg", "Remeras", "unisex", List.of("M"),
                ar.scraper.model.Product.MlScore.EMPTY, "Nike", "indumentaria", false, false,
                ar.scraper.model.Product.SenalCompra.EMPTY,
                ar.scraper.model.Product.SenalFinanciacion.EMPTY, 1);
    }

    private OffsetDateTime instante(String sql) throws Exception {
        try (var c = dataSource().getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as("query returned a row: %s", sql).isTrue();
            return rs.getObject(1, OffsetDateTime.class);
        }
    }

}
