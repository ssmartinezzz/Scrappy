package ar.scraper.db;

import ar.scraper.scrape.StatusEvent;
import ar.scraper.scrape.StatusEvents;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/** The database pushes; this class never queries it for state. */
@Component
public class DbNotificationListener implements SmartLifecycle {

    static final String CHANNEL = "status_events";
    static final String APPLICATION_NAME = "scrappy-listen";

    private static final Logger LOG = LoggerFactory.getLogger(DbNotificationListener.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int NOTIFY_WAIT_MS = 10_000;
    private static final long KEEPALIVE_MS = 30_000;
    private static final long STOP_JOIN_MS = 2_000;

    private final String url;
    private final String username;
    private final String password;
    private final StatusEvents bus;
    private final long retryInitialMs;
    private final long retryMaxMs;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean up;
    private volatile Connection connection;
    private volatile Thread thread;

    @Autowired
    public DbNotificationListener(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${db.listen.retry-initial-ms:1000}") long retryInitialMs,
            @Value("${db.listen.retry-max-ms:60000}") long retryMaxMs,
            StatusEvents bus) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.retryInitialMs = retryInitialMs;
        this.retryMaxMs = retryMaxMs;
        this.bus = bus;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startWhenReady() {
        start();
    }

    @Override
    public boolean isAutoStartup() {
        return false;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        Thread t = new Thread(this::loop, "pg-listen");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        Connection c = connection;
        if (c != null) {
            try {
                c.abort(Runnable::run);
            } catch (SQLException | RuntimeException e) {
                LOG.debug("[DB-LISTEN] abort on stop: {}", e.toString());
            }
        }
        Thread t = thread;
        if (t != null) {
            try {
                t.join(STOP_JOIN_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** True while a LISTEN connection is established. */
    public boolean isUp() {
        return up;
    }

    private void loop() {
        while (running.get()) {
            try {
                connectWithBackoff();
                if (!running.get()) return;
                pump();
            } catch (Exception e) {
                if (running.get()) LOG.debug("[DB-LISTEN] loop error: {}", e.toString());
            } finally {
                markDown();
                closeQuietly();
            }
        }
    }

    private void connectWithBackoff() throws Exception {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(Integer.MAX_VALUE)
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(retryInitialMs, 2.0, 0.5, retryMaxMs))
                .retryOnException(e -> running.get())
                .build();
        Retry retry = Retry.of("db-listen", config);
        retry.getEventPublisher().onRetry(ev -> {
            int attempt = ev.getNumberOfRetryAttempts();
            if (Integer.bitCount(attempt) == 1) {
                LOG.warn("[DB-LISTEN] still cannot listen (retry {}): {}", attempt,
                        ev.getLastThrowable() == null ? "" : ev.getLastThrowable().getMessage());
            }
        });
        retry.executeCallable(() -> {
            if (!running.get()) return null;
            connectAndListen();
            return null;
        });
    }

    private void connectAndListen() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", username);
        props.setProperty("password", password);
        props.setProperty("ApplicationName", APPLICATION_NAME);
        props.setProperty("tcpKeepAlive", "true");
        props.setProperty("connectTimeout", "10");
        props.setProperty("loginTimeout", "10");
        Connection c = DriverManager.getConnection(url, props);
        connection = c;
        try {
            c.setAutoCommit(true);
            try (Statement st = c.createStatement()) {
                st.execute("LISTEN " + CHANNEL);
                st.execute("SELECT 1");
            }
        } catch (SQLException | RuntimeException e) {
            closeQuietly();
            throw e;
        }
        if (!running.get()) {
            closeQuietly();
            return;
        }
        up = true;
        LOG.info("[DB-LISTEN] listening on '{}'", CHANNEL);
        bus.publish(new StatusEvent.Resync());
    }

    private void pump() throws SQLException {
        Connection c = connection;
        if (c == null) return;
        PGConnection pg = c.unwrap(PGConnection.class);
        long nextKeepalive = System.currentTimeMillis() + KEEPALIVE_MS;
        while (running.get()) {
            PGNotification[] batch = pg.getNotifications(NOTIFY_WAIT_MS);
            if (batch != null) {
                for (PGNotification n : batch) handle(n.getParameter());
            }
            if (System.currentTimeMillis() >= nextKeepalive) {
                try (Statement st = c.createStatement()) {
                    st.execute("SELECT 1");
                }
                nextKeepalive = System.currentTimeMillis() + KEEPALIVE_MS;
            }
        }
    }

    private void handle(String payload) {
        StatusEvent.DbChanged event = parse(payload);
        if (event == null) {
            LOG.debug("[DB-LISTEN] dropped a malformed payload");
            return;
        }
        bus.publish(event);
    }

    static StatusEvent.DbChanged parse(String payload) {
        try {
            JsonNode n = JSON.readTree(payload);
            if (n == null || !n.isObject() || !n.hasNonNull("t") || !n.hasNonNull("op")) return null;
            return new StatusEvent.DbChanged(
                    n.get("t").asText(), n.get("op").asText(),
                    longOrNull(n, "id"), longOrNull(n, "run"),
                    n.hasNonNull("site") ? n.get("site").asText() : null,
                    longOrNull(n, "job"),
                    n.hasNonNull("status") ? n.get("status").asText() : null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Long longOrNull(JsonNode n, String field) {
        return n.hasNonNull(field) && n.get(field).canConvertToLong() ? n.get(field).asLong() : null;
    }

    private void markDown() {
        if (up) {
            up = false;
            if (running.get()) LOG.warn("[DB-LISTEN] connection lost, reconnecting");
        }
    }

    private void closeQuietly() {
        Connection c = connection;
        connection = null;
        if (c == null) return;
        try {
            c.close();
        } catch (SQLException | RuntimeException ignored) {
        }
    }
}
