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

@DisplayName("V90 — rubro perfumeria: CHECK domain")
class V90RubroPerfumeriaTest extends PostgresTestBase {

    private static final String MIGRATION = "/db/migration/V90__rubro_perfumeria.sql";

    @Test
    @DisplayName("productos.rubro admits perfumeria and still rejects an unknown value")
    void checkAdmitsPerfumeria() throws Exception {
        enTransaccion(st -> {
            st.execute("INSERT INTO productos (url, sitio, nombre, precio, rubro) VALUES ('ok', 'V90 Test', 'P', 1, 'perfumeria')");
            assertThatThrownBy(() -> st.execute(
                    "INSERT INTO productos (url, sitio, nombre, precio, rubro) VALUES ('bad', 'V90 Test', 'P', 1, 'cosmetica')"))
                    .isInstanceOf(SQLException.class);
        });
    }

    @Test
    @DisplayName("The documented rollback sends perfumeria rows back to indumentaria and narrows the CHECK")
    void rollbackDocumentadoCorre() throws Exception {
        enTransaccion(st -> {
            st.execute("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) "
                    + "VALUES ('p', 'V90 Test', 'Eau de Parfum', 1, 'Perfume', 'indumentaria', true)");
            aplicar(st);
            st.execute("UPDATE productos SET rubro = 'perfumeria' WHERE url = 'p'");

            st.execute(DocumentedRollback.sqlFor("V90"));

            assertThat(rubro(st, "p")).isEqualTo("indumentaria");
            assertThatThrownBy(() -> st.execute(
                    "INSERT INTO productos (url, sitio, nombre, precio, rubro) VALUES ('x', 'V90 Test', 'P', 1, 'perfumeria')"))
                    .isInstanceOf(SQLException.class);
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
                st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) "
                        + "VALUES ('V90 Test', 'v90test', 'shopify', false, NULL, 'config') ON CONFLICT DO NOTHING");
                cuerpo.correr(st);
            } finally {
                c.rollback();
            }
        }
    }

    private static void aplicar(Statement st) throws Exception {
        try (InputStream in = V90RubroPerfumeriaTest.class.getResourceAsStream(MIGRATION)) {
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
