package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** The Java copy of the vocabulary is pinned by PlatformVocabularySyncTest. */
@DisplayName("V124 — odoo platform value")
class V124PlatformOdooTest extends PostgresTestBase {

    private static String check(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = 'sitio'::regclass AND conname = 'sitio_plataforma_check'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("the CHECK accepts odoo and keeps sfcc")
    void checkAcceptsOdoo() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(check(st)).contains("odoo").contains("sfcc");
        }
    }

    @Test
    @DisplayName("the documented rollback narrows the CHECK back to the V121 domain")
    void rollbackDocumentadoCorre() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V124"));
                assertThat(check(st)).doesNotContain("odoo").contains("sfcc");
            } finally {
                c.rollback();
            }
        }
    }
}
