package ar.scraper.db.support;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Makes one statement fail in the database, so a test can prove that the writes before it in
 * the same unit were rolled back. Close it to remove the trigger.
 */
public final class FaultInjection implements AutoCloseable {

    private final DataSource dataSource;
    private final String table;

    private FaultInjection(DataSource dataSource, String table) {
        this.dataSource = dataSource;
        this.table = table;
    }

    /**
     * @param event {@code INSERT}, {@code UPDATE} or {@code DELETE}
     * @param when  row condition ({@code NEW.x} / {@code OLD.x}), or {@code null} for every row
     */
    public static FaultInjection raiseOn(DataSource dataSource, String table, String event, String when)
            throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE OR REPLACE FUNCTION test_injected_fault() RETURNS trigger AS $$
                    BEGIN RAISE EXCEPTION 'injected fault'; END $$ LANGUAGE plpgsql""");
            st.execute("CREATE TRIGGER test_injected_fault BEFORE " + event + " ON " + table
                    + " FOR EACH ROW " + (when == null ? "" : "WHEN (" + when + ") ")
                    + "EXECUTE FUNCTION test_injected_fault()");
        }
        return new FaultInjection(dataSource, table);
    }

    @Override
    public void close() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TRIGGER IF EXISTS test_injected_fault ON " + table);
        }
    }
}
