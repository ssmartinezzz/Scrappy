package ar.scraper.db.migration;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("V49 migration — notificacion_precio outbox and the documented rollback")
class V49NotificacionPrecioTest extends PostgresTestBase {

    private static final String URL = "https://mrplacadeco.test/productos/placa-marmol";

    @Test
    @DisplayName("a new usuario is opted in, and a new row is PENDIENTE with zero attempts")
    void defaults() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                seedProducto(st);
                insertar(st, usuario, 900);

                assertThat(escalar(st, "SELECT notificar_bajas_precio FROM usuario WHERE id = '%s'".formatted(usuario)))
                        .isEqualTo("t");
                assertThat(escalar(st, "SELECT estado || '/' || intentos FROM notificacion_precio"))
                        .isEqualTo("PENDIENTE/0");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("the same usuario, url and new price cannot be queued twice; another price can")
    void dedup() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                seedProducto(st);
                insertar(st, usuario, 900);
                st.execute("SAVEPOINT s");
                assertThatThrownBy(() -> insertar(st, usuario, 900))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("notificacion_precio");
                st.execute("ROLLBACK TO SAVEPOINT s");
                insertar(st, usuario, 800);
                assertThat(escalar(st, "SELECT count(*) FROM notificacion_precio")).isEqualTo("2");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("estado is a closed vocabulary and ENVIADA requires enviada_at")
    void estadoCerrado() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                seedProducto(st);
                insertar(st, usuario, 900);
                st.execute("SAVEPOINT s");
                assertThatThrownBy(() -> st.executeUpdate("UPDATE notificacion_precio SET estado = 'OTRA'"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("chk_notificacion_precio_estado");
                st.execute("ROLLBACK TO SAVEPOINT s");
                assertThatThrownBy(() -> st.executeUpdate("UPDATE notificacion_precio SET estado = 'ENVIADA'"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("chk_notificacion_precio_enviada");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("deleting the usuario or the producto removes its queued notifications")
    void cascada() throws Exception {
        UUID usuario = UsuarioDePrueba.yo(dataSource());
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                seedProducto(st);
                insertar(st, usuario, 900);
                st.executeUpdate("DELETE FROM productos WHERE url = '%s'".formatted(URL));
                assertThat(escalar(st, "SELECT count(*) FROM notificacion_precio")).isEqualTo("0");

                seedProducto(st);
                insertar(st, usuario, 900);
                st.executeUpdate("DELETE FROM usuario WHERE id = '%s'".formatted(usuario));
                assertThat(escalar(st, "SELECT count(*) FROM notificacion_precio")).isEqualTo("0");
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    @DisplayName("favoritos is indexed by url, so a price drop finds its watchers without a scan")
    void favoritosIndexadoPorUrl() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            assertThat(escalar(st, "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_fav_url'"))
                    .isEqualTo("1");
        }
    }

    @Test
    @DisplayName("the documented rollback drops the table and the opt-in column and nothing else")
    void rollback() throws Exception {
        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(DocumentedRollback.sqlFor("V49"));

                assertThat(escalar(st, "SELECT to_regclass('notificacion_precio') IS NULL")).isEqualTo("t");
                assertThat(escalar(st, "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_fav_url'"))
                        .isEqualTo("0");
                assertThat(escalar(st, """
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_name = 'usuario' AND column_name = 'notificar_bajas_precio'
                        """)).isEqualTo("0");
                assertThat(escalar(st, "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'usuario' AND column_name = 'email'")).isEqualTo("1");
            } finally {
                c.rollback();
            }
        }
    }

    private static void seedProducto(Statement st) throws SQLException {
        st.executeUpdate("""
                INSERT INTO productos (url, sitio, nombre, precio, rubro, categoria)
                VALUES ('%s', 'Mrplacadeco', 'Placa Mármol Autoadhesiva', 900,
                        'construccion', 'Placa Decorativa')
                """.formatted(URL));
    }

    private static void insertar(Statement st, UUID usuario, double precioNuevo) throws SQLException {
        st.executeUpdate("""
                INSERT INTO notificacion_precio (usuario_id, url, precio_anterior, precio_nuevo)
                VALUES ('%s', '%s', 1000, %s)
                """.formatted(usuario, URL, precioNuevo));
    }

    private static String escalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
