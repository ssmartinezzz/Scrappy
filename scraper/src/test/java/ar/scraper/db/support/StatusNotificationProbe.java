package ar.scraper.db.support;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** A raw LISTEN connection on the test database, outside any pool. */
public final class StatusNotificationProbe implements AutoCloseable {

    public static final String CHANNEL = "status_events";

    private final Connection connection;
    private final PGConnection pg;

    public StatusNotificationProbe(DataSource dataSource) throws SQLException {
        SimpleDriverDataSource simple = (SimpleDriverDataSource) dataSource;
        this.connection = DriverManager.getConnection(simple.getUrl(), simple.getUsername(), simple.getPassword());
        this.connection.setAutoCommit(true);
        try (Statement st = connection.createStatement()) {
            st.execute("LISTEN " + CHANNEL);
        }
        this.pg = connection.unwrap(PGConnection.class);
    }

    /** Payloads that arrive within {@code waitMs}, in order; empty when nothing does. */
    public List<String> drain(int waitMs) throws SQLException {
        List<String> payloads = new ArrayList<>();
        PGNotification[] batch = pg.getNotifications(waitMs);
        while (batch != null && batch.length > 0) {
            for (PGNotification n : batch) payloads.add(n.getParameter());
            batch = pg.getNotifications(50);
        }
        return payloads;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
