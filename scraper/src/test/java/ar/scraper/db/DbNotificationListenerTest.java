package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.scrape.StatusEvent;
import ar.scraper.scrape.StatusEvents;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Status push")
@Feature("Database listener")
@Story("One LISTEN connection outside the pool, reconnecting with backoff")
@DisplayName("DbNotificationListener")
class DbNotificationListenerTest extends PostgresTestBase {

    private final List<StatusEvent> heard = new CopyOnWriteArrayList<>();
    private DbNotificationListener listener;

    private StatusEvents recorder() {
        return new StatusEvents() {
            @Override
            public void publish(StatusEvent event) {
                heard.add(event);
            }

            @Override
            public Subscription subscribe(Consumer<StatusEvent> l) {
                return () -> { };
            }
        };
    }

    private DbNotificationListener listenerFor(String url) {
        SimpleDriverDataSource ds = (SimpleDriverDataSource) dataSource();
        return new DbNotificationListener(url, ds.getUsername(), ds.getPassword(), 20, 100, recorder());
    }

    @BeforeEach
    void start() {
        listener = listenerFor(((SimpleDriverDataSource) dataSource()).getUrl());
    }

    @AfterEach
    void stop() {
        listener.stop();
    }

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    private long count(Class<?> type) {
        return heard.stream().filter(type::isInstance).count();
    }

    private void exec(String sql) throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private int listenerBackends() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM pg_stat_activity WHERE application_name = 'scrappy-listen'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @DisplayName("connects, announces a Resync, and turns a committed status change into DbChanged")
    void hearsACommittedChange() throws Exception {
        listener.start();
        await("the first Resync", () -> count(StatusEvent.Resync.class) == 1);
        assertThat(listener.isUp()).isTrue();

        exec("INSERT INTO scrape_run (scrape_uuid, started_at, status) VALUES (gen_random_uuid(), now(), 'RUNNING')");

        await("DbChanged", () -> count(StatusEvent.DbChanged.class) == 1);
        StatusEvent.DbChanged e = (StatusEvent.DbChanged) heard.stream()
                .filter(StatusEvent.DbChanged.class::isInstance).findFirst().orElseThrow();
        assertThat(e.table()).isEqualTo("scrape_run");
        assertThat(e.op()).isEqualTo("INSERT");
        assertThat(e.status()).isEqualTo("RUNNING");
        assertThat(e.id()).isNotNull();
    }

    @Test
    @DisplayName("a payload that is not JSON is dropped and the listener keeps hearing")
    void malformedPayloadIsDropped() throws Exception {
        listener.start();
        await("the first Resync", () -> count(StatusEvent.Resync.class) == 1);

        exec("SELECT pg_notify('status_events', 'not json')");
        exec("SELECT pg_notify('status_events', '{\"t\":\"scrape_run\"}')");
        exec("INSERT INTO scrape_run (scrape_uuid, started_at, status) VALUES (gen_random_uuid(), now(), 'RUNNING')");

        await("the valid change", () -> count(StatusEvent.DbChanged.class) >= 1);
        assertThat(count(StatusEvent.DbChanged.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("when the server kills the connection it reconnects, emits another Resync, and keeps hearing")
    void reconnectsAfterTheBackendIsTerminated() throws Exception {
        listener.start();
        await("the first Resync", () -> count(StatusEvent.Resync.class) == 1);

        exec("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = 'scrappy-listen'");

        await("the second Resync", () -> count(StatusEvent.Resync.class) == 2);
        await("up again", listener::isUp);

        exec("INSERT INTO scrape_run (scrape_uuid, started_at, status) VALUES (gen_random_uuid(), now(), 'RUNNING')");
        await("a change after the reconnect", () -> count(StatusEvent.DbChanged.class) == 1);
    }

    @Test
    @DisplayName("stop() ends the thread promptly and leaves no backend behind")
    void stopsCleanly() throws Exception {
        listener.start();
        await("up", listener::isUp);
        assertThat(listenerBackends()).isEqualTo(1);

        long t0 = System.nanoTime();
        listener.stop();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(ms).isLessThan(2_500);
        assertThat(listener.isRunning()).isFalse();
        assertThat(listener.isUp()).isFalse();
        await("the backend to disappear", () -> {
            try {
                return listenerBackends() == 0;
            } catch (Exception e) {
                return false;
            }
        });
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.getName().equals("pg-listen"));
    }

    @Test
    @DisplayName("an unreachable database never takes the app down: it keeps retrying and stops on request")
    void unreachableDatabaseKeepsRetrying() throws Exception {
        DbNotificationListener dead = listenerFor("jdbc:postgresql://127.0.0.1:1/none");
        try {
            dead.start();
            Thread.sleep(600);

            assertThat(dead.isRunning()).isTrue();
            assertThat(dead.isUp()).isFalse();
            assertThat(count(StatusEvent.Resync.class)).isZero();

            long t0 = System.nanoTime();
            dead.stop();
            assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(2_500);
            assertThat(dead.isRunning()).isFalse();
        } finally {
            dead.stop();
        }
    }

    @Test
    @DisplayName("parse: every notification shape V41 emits maps onto DbChanged; garbage maps to null")
    void parsesTheTriggerPayloads() {
        assertThat(DbNotificationListener.parse(
                "{\"t\": \"scrape_run_site\", \"op\": \"UPDATE\", \"run\": 7, \"site\": \"freres\", \"status\": \"DONE\"}"))
                .isEqualTo(new StatusEvent.DbChanged("scrape_run_site", "UPDATE", null, 7L, "freres", null, "DONE"));
        assertThat(DbNotificationListener.parse(
                "{\"t\": \"cron_execution\", \"op\": \"INSERT\", \"id\": 3, \"job\": 9, \"status\": \"running\"}"))
                .isEqualTo(new StatusEvent.DbChanged("cron_execution", "INSERT", 3L, null, null, 9L, "running"));
        assertThat(DbNotificationListener.parse("not json")).isNull();
        assertThat(DbNotificationListener.parse("[1,2]")).isNull();
        assertThat(DbNotificationListener.parse("{\"op\":\"INSERT\"}")).isNull();
    }
}
