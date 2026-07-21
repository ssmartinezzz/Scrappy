package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.cron.CronExecution;
import ar.scraper.cron.CronJob;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code cron_jobs}/{@code cron_executions} CRUD methods
 * added to {@link DatabaseService} (scraper-cronjobs PR1, Phase 1). Uses a
 * real temp-file SQLite connection via the package-private {@code initEn(path)}
 * test seam, mirroring {@link DatabaseServicePresetTest}.
 */
@Epic("Cron Scheduling")
@Feature("Persistence")
@Story("Cron persistence")
@DisplayName("DatabaseService — cron_jobs / cron_executions CRUD")
class DatabaseServiceCronTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        abrirBaseDeDatosTemporal();
    }

    @Step("Open temp-file SQLite DB and initialize schema")
    private void abrirBaseDeDatosTemporal() {
        db = new DatabaseService(dataSource());
    }


    private long crearJob(String name, String cronExpr) {
        return db.insertCronJob(name, 1000, 50000, List.of("Freres", "VCP"),
                true, false, cronExpr, true, "2026-07-05T03:00:00");
    }

    // ── cron_jobs CRUD ──────────────────────────────────────────────────────

    @Test
    void insertCronJobPersistsAllFieldsAndIsRetrievable() {
        long id = crearJob("Nightly full scrape", "0 0 3 * * *");

        assertThat(id).isPositive();
        CronJob job = db.getCronJob(id).orElseThrow();
        assertThat(job.name()).isEqualTo("Nightly full scrape");
        assertThat(job.precioMin()).isEqualTo(1000);
        assertThat(job.precioMax()).isEqualTo(50000);
        assertThat(job.sitios()).containsExactly("Freres", "VCP");
        assertThat(job.forceRetrain()).isTrue();
        assertThat(job.useGpu()).isFalse();
        assertThat(job.cronExpr()).isEqualTo("0 0 3 * * *");
        assertThat(job.enabled()).isTrue();
        assertThat(job.nextRunAt()).isEqualTo("2026-07-05T03:00:00");
        assertThat(job.createdAt()).isNotBlank();
        assertThat(job.updatedAt()).isNotBlank();
        assertThat(job.lastRunAt()).isNull();
    }

    @Test
    void insertCronJobWithEmptySitiosMeansAllSites() {
        Allure.parameter("sitios", List.of());
        long id = db.insertCronJob("Todos los sitios", 0, 100000, List.of(),
                false, true, "0 0 * * * *", true, null);

        CronJob job = db.getCronJob(id).orElseThrow();
        assertThat(job.sitios()).isEmpty();
    }

    @Test
    void listCronJobsReturnsAllInsertedJobsOrderedById() {
        long idA = crearJob("Job A", "0 0 3 * * *");
        long idB = crearJob("Job B", "0 0 4 * * *");

        List<CronJob> jobs = db.listCronJobs();

        assertThat(jobs).hasSize(2);
        assertThat(jobs.get(0).id()).isEqualTo(idA);
        assertThat(jobs.get(1).id()).isEqualTo(idB);
    }

    @Test
    void getCronJobReturnsEmptyForNonExistentId() {
        Allure.parameter("id", 999);
        assertThat(db.getCronJob(999)).isEmpty();
    }

    @Test
    void updateCronJobChangesFieldsAndReturnsTrue() {
        long id = crearJob("Original", "0 0 3 * * *");

        boolean ok = db.updateCronJob(id, "Editado", 2000, 60000, List.of("Sporting"),
                false, true, "0 0 5 * * *", false, "2026-07-06T05:00:00");

        assertThat(ok).isTrue();
        CronJob updated = db.getCronJob(id).orElseThrow();
        assertThat(updated.name()).isEqualTo("Editado");
        assertThat(updated.precioMin()).isEqualTo(2000);
        assertThat(updated.precioMax()).isEqualTo(60000);
        assertThat(updated.sitios()).containsExactly("Sporting");
        assertThat(updated.forceRetrain()).isFalse();
        assertThat(updated.useGpu()).isTrue();
        assertThat(updated.cronExpr()).isEqualTo("0 0 5 * * *");
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.nextRunAt()).isEqualTo("2026-07-06T05:00:00");
    }

    @Test
    void updateCronJobWithNonExistentIdReturnsFalse() {
        Allure.parameter("id", 999);
        boolean ok = db.updateCronJob(999, "x", 0, 1, List.of(), false, true, "0 0 * * * *", true, null);

        assertThat(ok).isFalse();
    }

    @Test
    void deleteCronJobRemovesJobAndCascadesItsExecutions() {
        long id = crearJob("A borrar", "0 0 3 * * *");
        db.insertCronExecution(id, "2026-07-05T03:00:00", "success", null);

        boolean deleted = db.deleteCronJob(id);

        assertThat(deleted).isTrue();
        assertThat(db.getCronJob(id)).isEmpty();
        assertThat(db.listExecutions(id, 50)).isEmpty();
    }

    @Test
    void deleteCronJobWithNonExistentIdReturnsFalse() {
        Allure.parameter("id", 999);
        assertThat(db.deleteCronJob(999)).isFalse();
    }

    @Test
    void touchLastRunAtUpdatesOnlyThatField() {
        long id = crearJob("Job", "0 0 3 * * *");

        boolean ok = db.touchLastRunAt(id, "2026-07-05T03:00:05");

        assertThat(ok).isTrue();
        CronJob job = db.getCronJob(id).orElseThrow();
        assertThat(job.lastRunAt()).isEqualTo("2026-07-05T03:00:05");
        assertThat(job.nextRunAt()).isEqualTo("2026-07-05T03:00:00"); // unchanged
    }

    @Test
    void updateNextRunAtUpdatesOnlyThatField() {
        long id = crearJob("Job", "0 0 3 * * *");

        boolean ok = db.updateNextRunAt(id, "2026-07-06T03:00:00");

        assertThat(ok).isTrue();
        CronJob job = db.getCronJob(id).orElseThrow();
        assertThat(job.nextRunAt()).isEqualTo("2026-07-06T03:00:00");
        assertThat(job.lastRunAt()).isNull(); // unchanged
    }

    // ── cron_executions CRUD ────────────────────────────────────────────────

    @Test
    void insertCronExecutionPersistsAndIsRetrievable() {
        long jobId = crearJob("Job", "0 0 3 * * *");

        long execId = db.insertCronExecution(jobId, "2026-07-05T03:00:00", "running", null);

        assertThat(execId).isPositive();
        CronExecution exec = db.getExecution(execId).orElseThrow();
        assertThat(exec.jobId()).isEqualTo(jobId);
        assertThat(exec.startedAt()).isEqualTo("2026-07-05T03:00:00");
        assertThat(exec.status()).isEqualTo("running");
        assertThat(exec.finishedAt()).isNull();
        assertThat(exec.durationMs()).isNull();
    }

    @Test
    void updateCronExecutionSetsFinishedFieldsAndLogOutput() {
        long jobId = crearJob("Job", "0 0 3 * * *");
        long execId = db.insertCronExecution(jobId, "2026-07-05T03:00:00", "running", null);

        boolean ok = db.updateCronExecution(execId, "2026-07-05T03:05:00", "success",
                null, "[INICIO] ...\n[FIN] ...", 300000);

        assertThat(ok).isTrue();
        CronExecution exec = db.getExecution(execId).orElseThrow();
        assertThat(exec.finishedAt()).isEqualTo("2026-07-05T03:05:00");
        assertThat(exec.status()).isEqualTo("success");
        assertThat(exec.logOutput()).contains("[INICIO]").contains("[FIN]");
        assertThat(exec.durationMs()).isEqualTo(300000);
    }

    @Test
    void updateCronExecutionWithNonExistentIdReturnsFalse() {
        Allure.parameter("execId", 999);
        assertThat(db.updateCronExecution(999, "x", "error", "boom", null, null)).isFalse();
    }

    @Test
    void insertCronExecutionRecordsSkippedReason() {
        Allure.parameter("status", "skipped");
        long jobId = crearJob("Job", "0 0 3 * * *");

        long execId = db.insertCronExecution(jobId, "2026-07-05T03:00:00", "skipped", "scrape en curso");

        CronExecution exec = db.getExecution(execId).orElseThrow();
        assertThat(exec.status()).isEqualTo("skipped");
        assertThat(exec.skippedReason()).isEqualTo("scrape en curso");
    }

    @Test
    void listExecutionsReturnsNewestFirst() {
        long jobId = crearJob("Job", "0 0 3 * * *");
        long id1 = db.insertCronExecution(jobId, "2026-07-05T03:00:00", "success", null);
        long id2 = db.insertCronExecution(jobId, "2026-07-06T03:00:00", "success", null);

        List<CronExecution> execs = db.listExecutions(jobId, 50);

        assertThat(execs).hasSize(2);
        assertThat(execs.get(0).id()).isEqualTo(id2);
        assertThat(execs.get(1).id()).isEqualTo(id1);
    }

    @Test
    void listExecutionsIsScopedPerJob() {
        long jobA = crearJob("Job A", "0 0 3 * * *");
        long jobB = crearJob("Job B", "0 0 4 * * *");
        db.insertCronExecution(jobA, "2026-07-05T03:00:00", "success", null);
        db.insertCronExecution(jobB, "2026-07-05T04:00:00", "success", null);

        assertThat(db.listExecutions(jobA, 50)).hasSize(1);
        assertThat(db.listExecutions(jobB, 50)).hasSize(1);
    }

    @Test
    void pruneCronExecutionsKeepsOnlyMostRecentN() {
        Allure.parameter("keep", 3);
        long jobId = crearJob("Job", "0 0 3 * * *");
        for (int i = 0; i < 5; i++) {
            db.insertCronExecution(jobId, "2026-07-0" + (i + 1) + "T03:00:00", "success", null);
        }

        db.pruneCronExecutions(jobId, 3);

        List<CronExecution> remaining = db.listExecutions(jobId, 100);
        assertThat(remaining).hasSize(3);
        // Newest-first: the 3 most recently inserted (highest ids) survive.
        assertThat(remaining).extracting(CronExecution::startedAt)
                .containsExactly("2026-07-05T03:00:00", "2026-07-04T03:00:00", "2026-07-03T03:00:00");
    }

    @Test
    void pruneCronExecutionsIsNoOpWhenUnderLimit() {
        Allure.parameter("keep", 50);
        long jobId = crearJob("Job", "0 0 3 * * *");
        db.insertCronExecution(jobId, "2026-07-05T03:00:00", "success", null);

        db.pruneCronExecutions(jobId, 50);

        assertThat(db.listExecutions(jobId, 100)).hasSize(1);
    }

    @Test
    void getExecutionReturnsEmptyForNonExistentId() {
        Allure.parameter("execId", 999);
        assertThat(db.getExecution(999)).isEmpty();
    }
}
