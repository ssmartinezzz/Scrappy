package ar.scraper.web;

import ar.scraper.cron.CronExecution;
import ar.scraper.cron.CronJob;
import ar.scraper.cron.CronJobService;
import ar.scraper.db.DatabaseService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the {@code /api/cron} REST layer (scraper-cronjobs PR1,
 * Phase 6). {@code CronApiController} is a plain {@code @RestController}
 * POJO — like every other controller test in this codebase, it is
 * instantiated directly with Mockito-mocked collaborators rather than via a
 * Spring MVC test slice (no {@code @WebMvcTest} convention exists in this
 * project — see {@code ApiControllerFinanciacionTest}).
 */
class CronApiControllerTest {

    private CronJobService cronJobService;
    private DatabaseService db;
    private CronApiController controller;

    @BeforeEach
    void setUp() {
        cronJobService = mock(CronJobService.class);
        db = mock(DatabaseService.class);
        controller = new CronApiController(cronJobService, db);
        // Valid unless a test overrides it — keeps the "happy path" tests terse.
        when(cronJobService.isValidCronExpr(anyString())).thenReturn(true);
    }

    private CronJob job(long id) {
        return new CronJob(id, "Nightly", 1000, 50000, List.of("Freres", "VCP"),
                true, false, "0 0 3 * * *", true,
                "2026-07-01T00:00:00", "2026-07-01T00:00:00", null, "2026-07-06T03:00:00");
    }

    private Map<String, Object> jobPayload() {
        return Map.of(
                "name", "Nightly",
                "precioMin", 1000.0,
                "precioMax", 50000.0,
                "sitios", List.of("Freres", "VCP"),
                "forceRetrain", true,
                "useGpu", false,
                "cronExpr", "0 0 3 * * *",
                "enabled", true);
    }

    // ── GET /api/cron ────────────────────────────────────────────────────────

    @Test
    void listReturnsAllJobs() {
        when(db.listCronJobs()).thenReturn(List.of(job(1), job(2)));

        ResponseEntity<?> resp = controller.listar();

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("jobs")).hasSize(2);
        assertThat(body.path("jobs").get(0).path("id").asLong()).isEqualTo(1L);
        assertThat(body.path("jobs").get(0).path("name").asText()).isEqualTo("Nightly");
        assertThat(body.path("jobs").get(0).path("sitios")).hasSize(2);
    }

    // ── GET /api/cron/{id} ───────────────────────────────────────────────────

    @Test
    void getReturnsJobWhenPresent() {
        when(db.getCronJob(1)).thenReturn(Optional.of(job(1)));

        ResponseEntity<?> resp = controller.obtener(1);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("id").asLong()).isEqualTo(1L);
        assertThat(body.path("cronExpr").asText()).isEqualTo("0 0 3 * * *");
        assertThat(body.path("useGpu").asBoolean()).isFalse();
    }

    @Test
    void getReturns404WhenAbsent() {
        when(db.getCronJob(999)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.obtener(999);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
    }

    // ── POST /api/cron ───────────────────────────────────────────────────────

    @Test
    void createHappyPathDelegatesToServiceAndReturnsCreatedJob() {
        when(cronJobService.createJob("Nightly", 1000.0, 50000.0, List.of("Freres", "VCP"),
                true, false, "0 0 3 * * *", true)).thenReturn(42L);
        when(db.getCronJob(42L)).thenReturn(Optional.of(job(42)));

        ResponseEntity<?> resp = controller.crear(jobPayload());

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("id").asLong()).isEqualTo(42L);
        verify(cronJobService).createJob("Nightly", 1000.0, 50000.0, List.of("Freres", "VCP"),
                true, false, "0 0 3 * * *", true);
    }

    @Test
    void createWithInvalidCronExprReturns400WithoutPersisting() {
        when(cronJobService.isValidCronExpr("bogus")).thenReturn(false);
        Map<String, Object> payload = new java.util.HashMap<>(jobPayload());
        payload.put("cronExpr", "bogus");

        ResponseEntity<?> resp = controller.crear(payload);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
        verify(cronJobService, never()).createJob(any(), anyDouble(), anyDouble(), any(),
                anyBoolean(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    void createWithBlankNameReturns400WithoutPersisting() {
        Map<String, Object> payload = new java.util.HashMap<>(jobPayload());
        payload.put("name", "  ");

        ResponseEntity<?> resp = controller.crear(payload);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(cronJobService, never()).createJob(any(), anyDouble(), anyDouble(), any(),
                anyBoolean(), anyBoolean(), any(), anyBoolean());
    }

    // ── PUT /api/cron/{id} ───────────────────────────────────────────────────

    @Test
    void updateHappyPathDelegatesToServiceAndReturnsUpdatedJob() {
        when(db.getCronJob(1L)).thenReturn(Optional.of(job(1)));
        when(cronJobService.updateJob(eq(1L), eq("Nightly"), eq(1000.0), eq(50000.0),
                eq(List.of("Freres", "VCP")), eq(true), eq(false), eq("0 0 3 * * *"), eq(true)))
                .thenReturn(true);

        ResponseEntity<?> resp = controller.actualizar(1, jobPayload());

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        verify(cronJobService).updateJob(1L, "Nightly", 1000.0, 50000.0, List.of("Freres", "VCP"),
                true, false, "0 0 3 * * *", true);
    }

    @Test
    void updateReturns404WhenAbsent() {
        when(db.getCronJob(999L)).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller.actualizar(999, jobPayload());

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verify(cronJobService, never()).updateJob(anyLong(), any(), anyDouble(), anyDouble(),
                any(), anyBoolean(), anyBoolean(), any(), anyBoolean());
    }

    @Test
    void updateWithInvalidCronExprReturns400WithoutPersisting() {
        when(db.getCronJob(1L)).thenReturn(Optional.of(job(1)));
        when(cronJobService.isValidCronExpr("bogus")).thenReturn(false);
        Map<String, Object> payload = new java.util.HashMap<>(jobPayload());
        payload.put("cronExpr", "bogus");

        ResponseEntity<?> resp = controller.actualizar(1, payload);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(cronJobService, never()).updateJob(anyLong(), any(), anyDouble(), anyDouble(),
                any(), anyBoolean(), anyBoolean(), any(), anyBoolean());
    }

    // ── DELETE /api/cron/{id} ────────────────────────────────────────────────

    @Test
    void deleteHappyPathReturnsOk() {
        when(db.deleteCronJob(1)).thenReturn(true);

        ResponseEntity<?> resp = controller.eliminar(1);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isTrue();
    }

    @Test
    void deleteReturns404WhenAbsent() {
        when(db.deleteCronJob(999)).thenReturn(false);

        ResponseEntity<?> resp = controller.eliminar(999);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    // ── GET /api/cron/{id}/executions ───────────────────────────────────────

    @Test
    void listExecutionsReturnsExecutionsForJobWithDefaultLimit() {
        CronExecution e1 = new CronExecution(1, 7, "2026-07-05T03:00:00", "2026-07-05T03:05:00",
                "success", null, "some captured log", 300000);
        when(db.listExecutions(7, 50)).thenReturn(List.of(e1));

        ResponseEntity<?> resp = controller.listarEjecuciones(7, 50);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("executions")).hasSize(1);
        assertThat(body.path("executions").get(0).path("status").asText()).isEqualTo("success");
        assertThat(body.path("executions").get(0).path("durationMs").asInt()).isEqualTo(300000);
        // No separate execution-detail endpoint exists in this batch (out of scope,
        // see apply-progress), so logOutput is included directly in the list —
        // otherwise it would be permanently unreachable via REST.
        assertThat(body.path("executions").get(0).path("logOutput").asText()).isEqualTo("some captured log");
    }

    // ── POST /api/cron/{id}/run-now ──────────────────────────────────────────

    @Test
    void runNowReturns404WhenJobAbsent() {
        when(cronJobService.triggerNow(999)).thenReturn(CronJobService.RunNowResult.NOT_FOUND);

        ResponseEntity<?> resp = controller.runNow(999);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
    }

    @Test
    void runNowReturns409WhenScraperBusyOrAlreadyInFlight() {
        when(cronJobService.triggerNow(1)).thenReturn(CronJobService.RunNowResult.BUSY);

        ResponseEntity<?> resp = controller.runNow(1);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isFalse();
    }

    @Test
    void runNowReturns202WhenStarted() {
        when(cronJobService.triggerNow(1)).thenReturn(CronJobService.RunNowResult.STARTED);

        ResponseEntity<?> resp = controller.runNow(1);

        assertThat(resp.getStatusCode().value()).isEqualTo(202);
        JsonNode body = (JsonNode) resp.getBody();
        assertThat(body.path("ok").asBoolean()).isTrue();
    }
}
