package ar.scraper.db;

import ar.scraper.cron.CronJob;
import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V9 — `cron_jobs.sitios_json` pasó a `cron_job_sitio`.
 *
 * <p>Era la última violación de 1FN sobre una columna que el backend
 * interpreta: de esa lista salen los sitios que el job scrapea. Lo que estos
 * tests fijan es lo mismo que en V7 con los talles: el orden sobrevive, una
 * lista que se ACHICA no deja sitios viejos vivos, y borrar el job se lleva
 * sus hijas.</p>
 */
@Epic("Persistence")
@Feature("Multi-value normalization")
@Story("cron_job_sitio — V9")
@DisplayName("V9 — los sitios de un cronjob viven en su propia tabla")
class CronJobSitiosRoundTripTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    private long crear(List<String> sitios) {
        return db.insertCronJob("Job", 1000, 50000, sitios, false, true, "0 0 3 * * *", true, null);
    }

    @Test
    @DisplayName("Los sitios round-trippean en su orden original")
    void sitiosRoundTripEnOrden() {
        long id = crear(List.of("Freres", "VCP", "Midway"));

        CronJob job = db.getCronJob(id).orElseThrow();

        assertThat(job.sitios()).containsExactly("Freres", "VCP", "Midway");
    }

    @Test
    @DisplayName("listCronJobs devuelve los sitios de cada job, sin mezclarlos")
    void listCronJobsNoMezclaLosSitios() {
        long a = crear(List.of("Freres"));
        long b = crear(List.of("VCP", "Midway"));

        List<CronJob> jobs = db.listCronJobs();

        assertThat(jobs).hasSize(2);
        assertThat(jobs.stream().filter(j -> j.id() == a).findFirst().orElseThrow().sitios())
                .containsExactly("Freres");
        assertThat(jobs.stream().filter(j -> j.id() == b).findFirst().orElseThrow().sitios())
                .containsExactly("VCP", "Midway");
    }

    @Test
    @DisplayName("Editar el job con menos sitios no deja los viejos vivos")
    void achicarLaListaNoDejaSitiosViejos() {
        long id = crear(List.of("Freres", "VCP", "Midway"));

        db.updateCronJob(id, "Job", 1000, 50000, List.of("VCP"), false, true, "0 0 3 * * *", true, null);

        assertThat(db.getCronJob(id).orElseThrow().sitios()).containsExactly("VCP");
        assertThat(contarSitios(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("Un job sin sitios es una lista vacía, no null")
    void jobSinSitios() {
        long id = crear(List.of());

        assertThat(db.getCronJob(id).orElseThrow().sitios()).isEmpty();
    }

    @Test
    @DisplayName("Borrar el job se lleva sus sitios por CASCADE")
    void borrarElJobCascadeaLosSitios() throws Exception {
        long id = crear(List.of("Freres", "VCP"));
        assertThat(contarSitios(id)).isEqualTo(2);

        db.deleteCronJob(id);

        assertThat(contarSitios(id)).isZero();
    }

    private int contarSitios(long jobId) {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM cron_job_sitio WHERE job_id=?")) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
