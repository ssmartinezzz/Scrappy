package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** The Java copy of the vocabulary is pinned by PlatformVocabularySyncTest. */
@DisplayName("V121 — sfcc platform value")
class V121PlatformSfccTest extends PostgresTestBase {

    private static String check(Statement st) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = 'sitio'::regclass AND conname = 'sitio_plataforma_check'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    @DisplayName("the CHECK accepts sfcc and keeps magento")
    void checkAcceptsSfcc() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(check(st)).contains("sfcc").contains("magento");
        }
    }

    @Test
    @DisplayName("the documented rollback narrows the CHECK back to the V91 domain")
    void rollbackDocumentadoCorre() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                // the odoo platform and its two sites sit on top of the sfcc value: newest first
                st.execute(DocumentedRollback.sqlFor("V138"));
                st.execute(DocumentedRollback.sqlFor("V127"));
                st.execute(DocumentedRollback.sqlFor("V126"));
                st.execute(DocumentedRollback.sqlFor("V125"));
                st.execute(DocumentedRollback.sqlFor("V124"));
                // the two sfcc sites sit on top of the platform value: newest first
                st.execute(DocumentedRollback.sqlFor("V123"));
                st.execute(DocumentedRollback.sqlFor("V122"));
                st.execute(DocumentedRollback.sqlFor("V121"));
                assertThat(check(st)).doesNotContain("sfcc").contains("magento");
            } finally {
                c.rollback();
            }
        }
    }
}
