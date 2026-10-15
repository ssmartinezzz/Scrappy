package ar.scraper.db;

import ar.scraper.catalog.UpsertStats;
import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.TestRepositories;
import ar.scraper.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("sp_upsert_run — a price drop of more than 5% queues one notification per watcher")
class SpUpsertRunNotificacionTest extends PostgresTestBase {

    private static final String URL = "https://site.com/baja";
    private static final String SITIO = "Sitio";

    private DatabaseService db;
    private JdbcTemplate jdbc;
    private UsuarioRepository usuarios;

    @BeforeEach
    void setUp() {
        db = TestDatabaseServices.create(dataSource());
        jdbc = new JdbcTemplate(dataSource());
        usuarios = TestRepositories.usuarios(dataSource());
        correr(1000.0);
    }

    private Product producto(double precio) {
        return Product.builder()
                .sitio(SITIO).nombre("Producto").precio(precio).precioOriginal(null)
                .url(URL).imagenUrl("http://img.example/x.jpg").categoria("Remera").genero("unisex")
                .talles(List.of("M")).ml(Product.MlScore.EMPTY).marca("Nike").rubro("indumentaria")
                .gymrat(false).marcaPremium(false).senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY).cantidadUnidades(1).build();
    }

    private UUID vigilante(String username, String email) {
        usuarios.crear(username, email, "$argon2id$de-prueba", false);
        UUID id = usuarios.buscarActivaPorUsername(username).orElseThrow().id();
        db.guardarFavorito(id, URL, SITIO, "Producto");
        return id;
    }

    private UpsertStats correr(double precio) {
        UpsertStats stats = db.upsertProductos(List.of(producto(precio)));
        assertThat(stats.nuevos() + stats.actualizados() + stats.sinCambios())
                .as("the upsert swallows SQL errors: zero rows processed means the write failed")
                .isEqualTo(1);
        return stats;
    }

    private int encoladas() {
        return jdbc.queryForObject("SELECT count(*) FROM notificacion_precio", Integer.class);
    }

    @Test
    @DisplayName("a drop of more than 5% queues a PENDIENTE row with both prices for the watcher")
    void bajaMayorAlCincoPorCiento() {
        UUID usuario = vigilante("usuario", "usuario@example.com");

        UpsertStats stats = correr(940.0);

        assertThat(stats.actualizados()).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                SELECT usuario_id, url, precio_anterior, precio_nuevo, estado FROM notificacion_precio
                """)).singleElement().satisfies(fila -> {
            assertThat(fila.get("usuario_id")).isEqualTo(usuario);
            assertThat(fila.get("url")).isEqualTo(URL);
            assertThat(fila.get("precio_anterior")).isEqualTo(1000.0);
            assertThat(fila.get("precio_nuevo")).isEqualTo(940.0);
            assertThat(fila.get("estado")).isEqualTo("PENDIENTE");
        });
    }

    @Test
    @DisplayName("exactly 5% does not queue; just past it does")
    void elUmbralEsEstricto() {
        vigilante("usuario", "usuario@example.com");

        correr(950.0);
        assertThat(encoladas()).isZero();

        correr(1000.0);
        correr(949.0);
        assertThat(encoladas()).isEqualTo(1);
    }

    @Test
    @DisplayName("a drop of more than 90% (a scrape glitch or a typo'd price) queues nothing; exactly 90% does")
    void unaBajaAbsurdaNoSeEncola() {
        vigilante("usuario", "usuario@example.com");

        correr(1.0);
        correr(1000.0);
        correr(99.0);
        assertThat(encoladas()).as("99.9% and 90.1% drops").isZero();

        correr(1000.0);
        correr(100.0);
        assertThat(encoladas()).as("exactly 90%").isEqualTo(1);
    }

    @Test
    @DisplayName("a price of zero is never announced as a drop")
    void precioCeroNoSeAnuncia() {
        vigilante("usuario", "usuario@example.com");

        correr(0.0);

        assertThat(encoladas()).isZero();
    }

    @Test
    @DisplayName("a smaller drop and a rise queue nothing")
    void bajaChicaYSubida() {
        vigilante("usuario", "usuario@example.com");

        correr(970.0);
        correr(1200.0);

        assertThat(encoladas()).isZero();
    }

    @Test
    @DisplayName("every watcher with an email gets a row; a usuario who does not watch it gets none")
    void unaFilaPorVigilante() {
        vigilante("usuario", "usuario@example.com");
        vigilante("otroUsuario", "otrousuario@example.com");
        usuarios.crear("ajeno", "ajeno@example.com", "$argon2id$de-prueba", false);

        correr(800.0);

        assertThat(encoladas()).isEqualTo(2);
    }

    @Test
    @DisplayName("no email, inactive, opted out and service accounts get nothing")
    void quienNoDebeRecibir() {
        vigilante("sinEmail", null);
        UUID inactivo = vigilante("inactivo", "inactivo@example.com");
        UUID optOut = vigilante("optOut", "optout@example.com");
        usuarios.crear("servicio", null, "$argon2id$de-prueba", true);
        db.guardarFavorito(usuarios.buscarActivaPorUsername("servicio").orElseThrow().id(), URL, SITIO, "Producto");
        jdbc.update("UPDATE usuario SET activo = false WHERE id = ?", inactivo);
        jdbc.update("UPDATE usuario SET notificar_bajas_precio = false WHERE id = ?", optOut);
        UUID receptor = vigilante("receptor", "receptor@example.com");

        correr(800.0);

        assertThat(jdbc.queryForList("SELECT usuario_id FROM notificacion_precio", UUID.class))
                .containsExactly(receptor);
    }

    @Test
    @DisplayName("an unowned favorito (no usuario) queues nothing")
    void favoritoSinDueno() {
        jdbc.update("INSERT INTO favoritos (url, sitio, nombre) VALUES (?, ?, ?)", URL, SITIO, "Producto");

        correr(800.0);

        assertThat(encoladas()).isZero();
    }

    @Test
    @DisplayName("falling back to the same price after a rise does not queue a duplicate")
    void sinDuplicados() {
        vigilante("usuario", "usuario@example.com");
        correr(800.0);
        correr(1000.0);

        correr(800.0);

        assertThat(encoladas()).isEqualTo(1);
    }

    @Test
    @DisplayName("the notification rolls back together with the price write")
    void seRevierteConElUpsert() throws Exception {
        vigilante("usuario", "usuario@example.com");

        try (Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("""
                        SELECT sp_upsert_run(jsonb_build_array(jsonb_build_object(
                            'url', '%s', 'sitio', '%s', 'nombre', 'Producto', 'precio', 500,
                            'now', now()::text, 'fecha', current_date::text)), false)
                        """.formatted(URL, SITIO));
                try (var rs = st.executeQuery("SELECT count(*) FROM notificacion_precio")) {
                    rs.next();
                    assertThat(rs.getInt(1)).as("queued inside the transaction").isEqualTo(1);
                }
            } finally {
                c.rollback();
            }
        }

        assertThat(encoladas()).isZero();
    }

    private Connection escuchar() throws Exception {
        Connection c = dataSource().getConnection();
        try (Statement st = c.createStatement()) {
            st.execute("LISTEN notificaciones");
        }
        return c;
    }

    /** NOTIFY is delivered at commit; a round trip on the listening connection flushes what is pending. */
    private static int avisosPendientes(Connection c) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute("SELECT 1");
        }
        PGNotification[] avisos = c.unwrap(PGConnection.class).getNotifications();
        return avisos == null ? 0 : avisos.length;
    }

    @Test
    @DisplayName("an upsert that queued something wakes the relay with a single pg_notify on 'notificaciones'")
    void despiertaAlRelay() throws Exception {
        vigilante("usuario", "usuario@example.com");
        try (Connection oyente = escuchar()) {
            correr(800.0);

            assertThat(encoladas()).isEqualTo(1);
            assertThat(avisosPendientes(oyente)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an upsert that queued nothing sends no wake-up")
    void sinEncolarNoDespierta() throws Exception {
        vigilante("usuario", "usuario@example.com");
        try (Connection oyente = escuchar()) {
            correr(970.0);

            assertThat(encoladas()).isZero();
            assertThat(avisosPendientes(oyente)).isZero();
        }
    }

    @Test
    @DisplayName("a rolled-back upsert wakes nobody")
    void revertidoNoDespierta() throws Exception {
        vigilante("usuario", "usuario@example.com");
        try (Connection oyente = escuchar(); Connection c = dataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("""
                        SELECT sp_upsert_run(jsonb_build_array(jsonb_build_object(
                            'url', '%s', 'sitio', '%s', 'nombre', 'Producto', 'precio', 500,
                            'now', now()::text, 'fecha', current_date::text)), false)
                        """.formatted(URL, SITIO));
            } finally {
                c.rollback();
            }

            assertThat(avisosPendientes(oyente)).isZero();
        }
    }
}
