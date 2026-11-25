package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("V112 — rubro perfumeria renamed belleza: CHECK domain and stored rows")
class V112RubroBellezaTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V112__rubro_belleza.sql";
    private static final String INSERT_ROW = "INSERT INTO productos (url, sitio, nombre, precio, rubro) VALUES ('%s', 'V112 Test', 'P', 1, %s)";

    @Test
    @DisplayName("the live schema admits belleza and rejects the old perfumeria and unknown values")
    void checkAdmitsBelleza() throws Exception {
        enTransaccion(st -> {
            st.execute(INSERT_ROW.formatted("ok", "'belleza'"));
            assertThatThrownBy(() -> st.execute(INSERT_ROW.formatted("old", "'perfumeria'"))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> st.execute(INSERT_ROW.formatted("bad", "'cosmetica'"))).isInstanceOf(SQLException.class);
        });
    }

    @Test
    @DisplayName("stored perfumeria rows move to belleza and every other rubro stays; re-applying changes nothing")
    void movesStoredRows() throws Exception {
        enTransaccion(st -> {
            st.execute(DocumentedRollback.sqlFor("V112"));
            st.execute(INSERT_ROW.formatted("p", "'perfumeria'"));
            st.execute(INSERT_ROW.formatted("r", "'indumentaria'"));
            st.execute(INSERT_ROW.formatted("n", "NULL"));
            aplicar(st);

            assertThat(rubro(st, "p")).isEqualTo("belleza");
            assertThat(rubro(st, "r")).isEqualTo("indumentaria");
            assertThat(rubro(st, "n")).isNull();
            aplicar(st);
            assertThat(rubro(st, "p")).as("idempotent").isEqualTo("belleza");
        });
    }

    @Test
    @DisplayName("The documented rollback sends Perfume/Desodorante back to perfumeria, any later belleza category to the site default, and narrows the CHECK")
    void rollbackDocumentadoCorre() throws Exception {
        enTransaccion(st -> {
            st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                    + "VALUES ('V112 Toys', 'v112toys', 'shopify', false, NULL, 'config')");
            st.execute("UPDATE sitio SET rubro_por_defecto = 'juguetes' WHERE sitio_key = 'v112toys'");
            st.execute("""
                    INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro) VALUES
                        ('perfume', 'V112 Test', 'Eau de Parfum', 1, 'Perfume',     'belleza'),
                        ('deo',     'V112 Test', 'Desodorante',   1, 'Desodorante', 'belleza'),
                        ('rimel',   'V112 Toys', 'Rimel',         1, 'Otros',       'belleza'),
                        ('sin',     'V112 Test', 'Labial',        1, 'Otros',       'belleza')
                    """);

            st.execute(DocumentedRollback.sqlFor("V112"));

            assertThat(rubro(st, "perfume")).isEqualTo("perfumeria");
            assertThat(rubro(st, "deo")).isEqualTo("perfumeria");
            assertThat(rubro(st, "rimel")).isEqualTo("juguetes");
            assertThat(rubro(st, "sin")).isEqualTo("indumentaria");
            assertThatThrownBy(() -> st.execute(INSERT_ROW.formatted("x", "'belleza'"))).isInstanceOf(SQLException.class);
        });
    }

    private interface Cuerpo {
        void correr(Statement st) throws Exception;
    }

    private void enTransaccion(Cuerpo cuerpo) throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET CONSTRAINTS ALL IMMEDIATE");
                // V138 replaced the rubro CHECK with a foreign key: its rollback restores the CHECK first.
                st.execute(DocumentedRollback.sqlFor("V140"));
                st.execute(DocumentedRollback.sqlFor("V139"));
                st.execute(DocumentedRollback.sqlFor("V138"));
                st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                        + "VALUES ('V112 Test', 'v112test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static void aplicar(Statement st) throws Exception {
        try (InputStream in = V112RubroBellezaTest.class.getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s on the classpath", MIGRATION).isNotNull();
            st.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String rubro(Statement st, String url) throws Exception {
        try (ResultSet rs = st.executeQuery("SELECT rubro FROM productos WHERE url = '" + url + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
