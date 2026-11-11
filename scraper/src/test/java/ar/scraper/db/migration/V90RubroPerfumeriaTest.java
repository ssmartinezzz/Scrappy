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

@DisplayName("V90 — rubro perfumeria: CHECK domain and realignment of stored Perfume rows")
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
    @DisplayName("Perfume rows move to perfumeria; locked rows, other categories and forced tecnologia/oficina sites stay")
    void realignsStoredPerfume() throws Exception {
        enTransaccion(st -> {
            st.execute("INSERT INTO sitio (nombre, sitio_key, plataforma, es_premium, rubro_forzado, origen) VALUES "
                    + "('V90 Tech', 'v90tech', 'shopify', false, 'tecnologia', 'config'), "
                    + "('V90 Office', 'v90office', 'shopify', false, 'oficina', 'config')");
            st.execute("""
                    INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo, bloqueado_por, bloqueado_at) VALUES
                        ('p-indu',   'V90 Test',   'Eau de Parfum',  1, 'Perfume', 'indumentaria', true, NULL,    NULL),
                        ('p-null',   'V90 Test',   'Colonia',        1, 'Perfume', NULL,           true, NULL,    NULL),
                        ('p-juguete','V90 Test',   'Body Splash',    1, 'Perfume', 'juguetes',     true, NULL,    NULL),
                        ('p-lock',   'V90 Test',   'Perfume lock',   1, 'Perfume', 'indumentaria', true, 'local', now()),
                        ('p-tech',   'V90 Tech',   'Perfume tech',   1, 'Perfume', 'tecnologia',   true, NULL,    NULL),
                        ('p-office', 'V90 Office', 'Perfume office', 1, 'Perfume', 'oficina',      true, NULL,    NULL),
                        ('remera',   'V90 Test',   'Remera',         1, 'Remera',  'indumentaria', true, NULL,    NULL)
                    """);
            aplicar(st);

            assertThat(rubro(st, "p-indu")).isEqualTo("perfumeria");
            assertThat(rubro(st, "p-null")).isEqualTo("perfumeria");
            assertThat(rubro(st, "p-juguete")).isEqualTo("perfumeria");
            assertThat(rubro(st, "p-lock")).isEqualTo("indumentaria");
            assertThat(rubro(st, "p-tech")).isEqualTo("tecnologia");
            assertThat(rubro(st, "p-office")).isEqualTo("oficina");
            assertThat(rubro(st, "remera")).isEqualTo("indumentaria");
        });
    }

    @Test
    @DisplayName("The documented rollback sends perfumeria rows back to indumentaria and narrows the CHECK")
    void rollbackDocumentadoCorre() throws Exception {
        enTransaccion(st -> {
            st.execute("INSERT INTO productos (url, sitio, nombre, precio, categoria, rubro, activo) "
                    + "VALUES ('p', 'V90 Test', 'Eau de Parfum', 1, 'Perfume', 'indumentaria', true)");
            aplicar(st);
            assertThat(rubro(st, "p")).isEqualTo("perfumeria");

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
