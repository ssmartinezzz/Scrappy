package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.StatusNotificationProbe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Persistence")
@Feature("Status push")
@Story("The database announces status changes, after commit and only when the status changed")
@DisplayName("V41 — pg_notify on scrape_run, scrape_run_site and cron_executions")
class V41StatusNotifyTest extends PostgresTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SITIO_KEY = "freres";

    private StatusNotificationProbe probe;

    @BeforeEach
    void listen() throws Exception {
        probe = new StatusNotificationProbe(dataSource());
    }

    @AfterEach
    void stopListening() throws Exception {
        probe.close();
    }

    @Test
    @DisplayName("Nothing is heard before COMMIT; exactly one notification after it")
    void notifiesOnlyAfterCommit() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            long id = insertRun(c, "RUNNING");
            assertThat(probe.drain(400)).as("uncommitted write is silent").isEmpty();
            c.commit();

            List<String> heard = probe.drain(3000);
            assertThat(heard).hasSize(1);
            JsonNode n = JSON.readTree(heard.get(0));
            assertThat(n.get("t").asText()).isEqualTo("scrape_run");
            assertThat(n.get("op").asText()).isEqualTo("INSERT");
            assertThat(n.get("id").asLong()).isEqualTo(id);
            assertThat(n.get("status").asText()).isEqualTo("RUNNING");
        }
    }

    @Test
    @DisplayName("A rolled-back write announces nothing")
    void rollbackIsSilent() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            insertRun(c, "RUNNING");
            c.rollback();
        }
        assertThat(probe.drain(600)).isEmpty();
    }

    @Test
    @DisplayName("An UPDATE that leaves status unchanged is silent; one that changes it is announced")
    void onlyStatusChangesAreAnnounced() throws Exception {
        long id;
        try (Connection c = dataSource().getConnection()) {
            id = insertRun(c, "RUNNING");
        }
        assertThat(probe.drain(3000)).hasSize(1);

        exec("UPDATE scrape_run SET productos_count = 7 WHERE id = " + id);
        exec("UPDATE scrape_run SET status = status WHERE id = " + id);
        assertThat(probe.drain(600)).as("status did not change").isEmpty();

        exec("UPDATE scrape_run SET status = 'COMPLETED', finished_at = now() WHERE id = " + id);
        List<String> heard = probe.drain(3000);
        assertThat(heard).hasSize(1);
        JsonNode n = JSON.readTree(heard.get(0));
        assertThat(n.get("op").asText()).isEqualTo("UPDATE");
        assertThat(n.get("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("scrape_run_site carries run, site and status")
    void siteRowPayload() throws Exception {
        long run;
        try (Connection c = dataSource().getConnection()) {
            run = insertRun(c, "RUNNING");
        }
        probe.drain(3000);

        exec("INSERT INTO scrape_run_site (scrape_run_id, sitio_key, status) VALUES ("
                + run + ", '" + SITIO_KEY + "', 'PENDING')");
        exec("UPDATE scrape_run_site SET status = 'DONE', productos_count = 3 WHERE scrape_run_id = "
                + run + " AND sitio_key = '" + SITIO_KEY + "'");

        List<String> heard = probe.drain(3000);
        assertThat(heard).hasSize(2);
        JsonNode done = JSON.readTree(heard.get(1));
        assertThat(done.get("t").asText()).isEqualTo("scrape_run_site");
        assertThat(done.get("op").asText()).isEqualTo("UPDATE");
        assertThat(done.get("run").asLong()).isEqualTo(run);
        assertThat(done.get("site").asText()).isEqualTo(SITIO_KEY);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("cron_executions carries job and status, and never the free-text columns")
    void cronExecutionPayloadHasNoFreeText() throws Exception {
        long job = insertCronJob();
        String huge = "x".repeat(20_000);
        long exec;
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "INSERT INTO cron_executions (job_id, started_at, status, log_output) VALUES ("
                             + job + ", now(), 'RUNNING', '" + huge + "') RETURNING id")) {
            rs.next();
            exec = rs.getLong(1);
        }

        List<String> heard = probe.drain(3000);
        assertThat(heard).hasSize(1);
        assertThat(heard.get(0).length()).isLessThan(300);
        JsonNode n = JSON.readTree(heard.get(0));
        assertThat(n.get("t").asText()).isEqualTo("cron_execution");
        assertThat(n.get("id").asLong()).isEqualTo(exec);
        assertThat(n.get("job").asLong()).isEqualTo(job);
        assertThat(n.get("status").asText()).isEqualTo("RUNNING");
        assertThat(n.fieldNames()).toIterable().containsExactlyInAnyOrder("t", "op", "id", "job", "status");
    }

    @Test
    @DisplayName("DELETE announces nothing")
    void deleteIsSilent() throws Exception {
        long id;
        try (Connection c = dataSource().getConnection()) {
            id = insertRun(c, "RUNNING");
        }
        probe.drain(3000);
        exec("DELETE FROM scrape_run WHERE id = " + id);
        assertThat(probe.drain(600)).isEmpty();
    }

    private long insertRun(Connection c, String status) throws Exception {
        String finished = "RUNNING".equals(status) ? "NULL" : "now()";
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "INSERT INTO scrape_run (scrape_uuid, started_at, finished_at, status) VALUES "
                             + "(gen_random_uuid(), now(), " + finished + ", '" + status + "') RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long insertCronJob() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "INSERT INTO cron_jobs (name, cron_expr, created_at, updated_at) "
                             + "VALUES ('v41', '0 0 * * * *', now(), now()) RETURNING id")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void exec(String sql) throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
