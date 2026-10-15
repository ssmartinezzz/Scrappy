package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.TestRepositories;
import ar.scraper.db.support.TestTransactions;
import ar.scraper.notificaciones.BajaReclamada;
import ar.scraper.notificaciones.FalloDeEnvio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NotificacionOutboxRepository — claim, lease, mark and backoff")
class NotificacionOutboxRepositoryTest extends PostgresTestBase {

    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration BACKOFF = Duration.ofSeconds(60);
    private static final int MAX = 3;

    private NotificacionOutboxRepository outbox;
    private JdbcTemplate jdbc;
    private UsuarioRepository usuarios;
    private UUID usuario;

    @BeforeEach
    void setUp() {
        outbox = TestRepositories.notificaciones(dataSource());
        jdbc = new JdbcTemplate(dataSource());
        usuarios = TestRepositories.usuarios(dataSource());
        usuario = nuevoUsuario("usuario", "usuario@example.com");
    }

    private UUID nuevoUsuario(String username, String email) {
        usuarios.crear(username, email, "$argon2id$de-prueba", false);
        return usuarios.buscarActivaPorUsername(username).orElseThrow().id();
    }

    private long encolar(UUID dueno, String url, double anterior, double nuevo) {
        jdbc.update("""
                INSERT INTO productos (url, sitio, nombre, precio, rubro, categoria)
                VALUES (?, 'Mrplacadeco', ?, ?, 'construccion', 'Placa Decorativa')
                ON CONFLICT (url) DO NOTHING
                """, url, "Nombre " + url, nuevo);
        return jdbc.queryForObject("""
                INSERT INTO notificacion_precio (usuario_id, url, precio_anterior, precio_nuevo)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, dueno, url, anterior, nuevo);
    }

    private String estado(long id) {
        return jdbc.queryForObject("SELECT estado FROM notificacion_precio WHERE id = ?", String.class, id);
    }

    private int intentos(long id) {
        return jdbc.queryForObject("SELECT intentos FROM notificacion_precio WHERE id = ?", Integer.class, id);
    }

    @Test
    @DisplayName("claims a due row with its recipient and product, counts the attempt and leases it")
    void reclamaConLease() {
        long id = encolar(usuario, "https://s.test/a", 1000, 800);

        List<BajaReclamada> reclamadas = outbox.reclamar(10, MAX, LEASE);

        assertThat(reclamadas).singleElement().satisfies(b -> {
            assertThat(b.id()).isEqualTo(id);
            assertThat(b.usuarioId()).isEqualTo(usuario);
            assertThat(b.email()).isEqualTo("usuario@example.com");
            assertThat(b.nombre()).isEqualTo("Nombre https://s.test/a");
            assertThat(b.sitio()).isEqualTo("Mrplacadeco");
            assertThat(b.precioAnterior()).isEqualTo(1000.0);
            assertThat(b.precioNuevo()).isEqualTo(800.0);
        });
        assertThat(intentos(id)).isEqualTo(1);
        assertThat(outbox.reclamar(10, MAX, LEASE)).as("the lease hides it").isEmpty();
    }

    @Test
    @DisplayName("the limit counts recipients, not rows: a claimed user brings every due row, oldest user first")
    void limitePorDestinatario() {
        UUID otro = nuevoUsuario("otro", "otro@example.com");
        List<Long> delPrimero = new ArrayList<>();
        delPrimero.add(encolar(usuario, "https://s.test/a", 1000, 800));
        encolar(otro, "https://s.test/b", 1000, 800);
        delPrimero.add(encolar(usuario, "https://s.test/c", 1000, 800));
        delPrimero.add(encolar(usuario, "https://s.test/d", 1000, 800));

        assertThat(outbox.reclamar(1, MAX, LEASE)).extracting(BajaReclamada::id)
                .containsExactlyInAnyOrderElementsOf(delPrimero);
        assertThat(outbox.reclamar(1, MAX, LEASE)).extracting(BajaReclamada::usuarioId).containsExactly(otro);
    }

    @Test
    @DisplayName("two claimers running at the same time never share a recipient, so one user is never split in two mails")
    void dosReclamadoresNoPartenAUnUsuario() throws Exception {
        UUID otro = nuevoUsuario("otro", "otro@example.com");
        for (int i = 0; i < 6; i++) encolar(usuario, "https://s.test/a" + i, 1000, 800);
        for (int i = 0; i < 2; i++) encolar(otro, "https://s.test/b" + i, 1000, 800);
        var tm = TestTransactions.manager(dataSource());
        CountDownLatch primeroReclamo = new CountDownLatch(1);
        CountDownLatch segundoTermino = new CountDownLatch(1);

        CompletableFuture<List<BajaReclamada>> primero = CompletableFuture.supplyAsync(() ->
                new TransactionTemplate(tm).execute(status -> {
                    List<BajaReclamada> mias = outbox.reclamar(1, MAX, LEASE);
                    primeroReclamo.countDown();
                    try {
                        assertThat(segundoTermino.await(10, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return mias;
                }));
        assertThat(primeroReclamo.await(10, TimeUnit.SECONDS)).isTrue();
        List<BajaReclamada> segundo = outbox.reclamar(5, MAX, LEASE);
        segundoTermino.countDown();

        List<BajaReclamada> delPrimero = primero.get(10, TimeUnit.SECONDS);
        assertThat(delPrimero).as("all of one user's rows").hasSize(6)
                .extracting(BajaReclamada::usuarioId).containsOnly(usuario);
        assertThat(segundo).as("the user the first claimer holds is skipped, not waited for, not split")
                .hasSize(2).extracting(BajaReclamada::usuarioId).containsOnly(otro);
    }

    private void usuariosPendientes(int cuantos) {
        for (int i = 0; i < cuantos; i++) {
            UUID u = nuevoUsuario("pendiente" + i, "pendiente" + i + "@example.com");
            encolar(u, "https://s.test/p" + i, 1000, 800);
        }
    }

    private void marcarEnviadaHace(UUID dueno, String antes) {
        jdbc.update("""
                UPDATE notificacion_precio SET estado = 'ENVIADA', enviada_at = now() - ?::interval
                 WHERE usuario_id = ?""", antes, dueno);
    }

    @Test
    @DisplayName("a cap leaves room for what the last minute has not already sent: 2 sent of 3 allows 1 more")
    void topeDescuentaLoEnviadoEnElUltimoMinuto() {
        for (int i = 0; i < 2; i++) {
            UUID u = nuevoUsuario("enviado" + i, "enviado" + i + "@example.com");
            encolar(u, "https://s.test/e" + i, 1000, 800);
            marcarEnviadaHace(u, "10 seconds");
        }
        usuariosPendientes(4);

        assertThat(outbox.reclamar(10, MAX, LEASE, 3)).extracting(BajaReclamada::usuarioId).hasSize(1);
        assertThat(outbox.reclamar(10, MAX, LEASE, 3)).as("the claim itself is now in flight").isEmpty();
    }

    @Test
    @DisplayName("sends older than a minute no longer count against the cap")
    void lasEnviadasViejasNoCuentan() {
        for (int i = 0; i < 2; i++) {
            UUID u = nuevoUsuario("enviado" + i, "enviado" + i + "@example.com");
            encolar(u, "https://s.test/e" + i, 1000, 800);
            marcarEnviadaHace(u, "61 seconds");
        }
        usuariosPendientes(4);

        assertThat(outbox.reclamar(10, MAX, LEASE, 2)).extracting(BajaReclamada::usuarioId).hasSize(2);
    }

    @Test
    @DisplayName("a recipient another backend holds under an unexpired lease counts as in flight")
    void losLeasesVigentesCuentan() {
        usuariosPendientes(5);
        assertThat(outbox.reclamar(3, MAX, LEASE)).hasSize(3);

        assertThat(outbox.reclamar(10, MAX, LEASE, 4)).as("cap 4, 3 in flight").extracting(BajaReclamada::usuarioId).hasSize(1);
    }

    @Test
    @DisplayName("a row in backoff after a failure is not in flight, and neither is one whose lease expired")
    void elBackoffYElLeaseVencidoNoCuentan() {
        UUID a = nuevoUsuario("a", "a@example.com");
        UUID b = nuevoUsuario("b", "b@example.com");
        long fallida = encolar(a, "https://s.test/a", 1000, 800);
        encolar(b, "https://s.test/b", 1000, 800);
        outbox.reclamar(10, MAX, LEASE);
        outbox.registrarFallos(List.of(new FalloDeEnvio(fallida, "smtp caído")), MAX, BACKOFF);
        jdbc.update("UPDATE notificacion_precio SET lease_hasta = now() - interval '1 second' WHERE lease_hasta IS NOT NULL");
        usuariosPendientes(3);

        assertThat(outbox.reclamar(10, MAX, LEASE, 2)).extracting(BajaReclamada::usuarioId).hasSize(2);
    }

    @Test
    @DisplayName("marking sent or failed releases the lease")
    void marcarLiberaElLease() {
        long enviada = encolar(usuario, "https://s.test/a", 1000, 800);
        UUID otro = nuevoUsuario("otro", "otro@example.com");
        long fallida = encolar(otro, "https://s.test/b", 1000, 800);
        outbox.reclamar(10, MAX, LEASE);

        outbox.marcarEnviadas(List.of(enviada));
        outbox.registrarFallos(List.of(new FalloDeEnvio(fallida, "smtp caído")), MAX, BACKOFF);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notificacion_precio WHERE lease_hasta IS NOT NULL",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("zero means no cap, however much was sent or is in flight")
    void sinTopeNoDescuentaNada() {
        for (int i = 0; i < 3; i++) {
            UUID u = nuevoUsuario("enviado" + i, "enviado" + i + "@example.com");
            encolar(u, "https://s.test/e" + i, 1000, 800);
            marcarEnviadaHace(u, "1 second");
        }
        usuariosPendientes(3);

        assertThat(outbox.reclamar(10, MAX, LEASE, 0)).hasSize(3);
    }

    @Test
    @DisplayName("two backends claiming at the same moment under a cap never exceed it together")
    void dosReclamadoresRespetanElTopeJuntos() throws Exception {
        usuariosPendientes(20);
        var tm = TestTransactions.manager(dataSource());
        CountDownLatch salida = new CountDownLatch(1);

        List<CompletableFuture<List<BajaReclamada>>> reclamos = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            reclamos.add(CompletableFuture.supplyAsync(() -> {
                try {
                    assertThat(salida.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new TransactionTemplate(tm).execute(status -> outbox.reclamar(20, MAX, LEASE, 5));
            }));
        }
        salida.countDown();

        int total = 0;
        for (var reclamo : reclamos) total += reclamo.get(20, TimeUnit.SECONDS).size();
        assertThat(total).isEqualTo(5);
    }

    @Test
    @DisplayName("a row whose lease expired is claimed again, with the attempt counted")
    void leaseVencido() {
        long id = encolar(usuario, "https://s.test/a", 1000, 800);
        outbox.reclamar(10, MAX, Duration.ZERO);

        assertThat(outbox.reclamar(10, MAX, LEASE)).extracting(BajaReclamada::id).containsExactly(id);
        assertThat(intentos(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("a row whose attempts ran out with an expired lease is given up on, not claimed")
    void leaseVencidoAgotado() {
        long id = encolar(usuario, "https://s.test/a", 1000, 800);
        for (int i = 0; i < MAX; i++) outbox.reclamar(10, MAX, Duration.ZERO);

        assertThat(outbox.reclamar(10, MAX, LEASE)).isEmpty();
        assertThat(estado(id)).isEqualTo("FALLIDA");
    }

    @Test
    @DisplayName("marking as sent stamps enviada_at and the row is never claimed again")
    void marcarEnviadas() {
        long id = encolar(usuario, "https://s.test/a", 1000, 800);
        outbox.reclamar(10, MAX, Duration.ZERO);

        outbox.marcarEnviadas(List.of(id));

        assertThat(estado(id)).isEqualTo("ENVIADA");
        assertThat(jdbc.queryForObject("SELECT enviada_at IS NOT NULL FROM notificacion_precio WHERE id = ?",
                Boolean.class, id)).isTrue();
        assertThat(outbox.reclamar(10, MAX, LEASE)).isEmpty();
    }

    @Test
    @DisplayName("a failure keeps the error and backs off exponentially: base after one attempt, double after two")
    void backoffExponencial() {
        long id = encolar(usuario, "https://s.test/a", 1000, 800);

        outbox.reclamar(10, MAX, Duration.ZERO);
        outbox.registrarFallos(List.of(new FalloDeEnvio(id, "smtp caído")), MAX, BACKOFF);
        assertThat(segundosHastaElProximoIntento(id)).isBetween(55.0, 61.0);
        assertThat(jdbc.queryForObject("SELECT ultimo_error FROM notificacion_precio WHERE id = ?",
                String.class, id)).isEqualTo("smtp caído");
        assertThat(outbox.reclamar(10, MAX, LEASE)).as("not due yet").isEmpty();

        jdbc.update("UPDATE notificacion_precio SET proximo_intento_at = now() WHERE id = ?", id);
        outbox.reclamar(10, MAX, Duration.ZERO);
        outbox.registrarFallos(List.of(new FalloDeEnvio(id, "smtp caído")), MAX, BACKOFF);
        assertThat(segundosHastaElProximoIntento(id)).isBetween(115.0, 121.0);
        assertThat(estado(id)).isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("one call records a different error per row and backs each off from its own attempt count")
    void variosFallosEnUnaSolaSentencia() {
        long uno = encolar(usuario, "https://s.test/a", 1000, 800);
        long dos = encolar(usuario, "https://s.test/b", 1000, 800);
        long ajena = encolar(usuario, "https://s.test/c", 1000, 800);
        outbox.reclamar(10, MAX, Duration.ZERO);
        jdbc.update("UPDATE notificacion_precio SET proximo_intento_at = now() + interval '1 hour' WHERE id <> ?", dos);
        jdbc.update("UPDATE notificacion_precio SET proximo_intento_at = now() WHERE id = ?", dos);
        outbox.reclamar(10, MAX, Duration.ZERO);

        outbox.registrarFallos(List.of(new FalloDeEnvio(uno, "error uno"), new FalloDeEnvio(dos, "error dos")),
                MAX, BACKOFF);

        assertThat(jdbc.queryForObject("SELECT ultimo_error FROM notificacion_precio WHERE id = ?", String.class, uno))
                .isEqualTo("error uno");
        assertThat(jdbc.queryForObject("SELECT ultimo_error FROM notificacion_precio WHERE id = ?", String.class, dos))
                .isEqualTo("error dos");
        assertThat(segundosHastaElProximoIntento(uno)).isBetween(55.0, 61.0);
        assertThat(segundosHastaElProximoIntento(dos)).isBetween(115.0, 121.0);
        assertThat(jdbc.queryForObject("SELECT ultimo_error FROM notificacion_precio WHERE id = ?", String.class, ajena))
                .as("a row not in the call is untouched").isNull();
    }

    @Test
    @DisplayName("a permanent failure makes the row FALLIDA on the first attempt and keeps the reason; a transient one in the same call backs off")
    void rechazoPermanenteEsFallidaAlInstante() {
        long permanente = encolar(usuario, "https://s.test/a", 1000, 800);
        UUID otro = nuevoUsuario("otro", "otro@example.com");
        long transitoria = encolar(otro, "https://s.test/b", 1000, 800);
        outbox.reclamar(10, MAX, LEASE);

        outbox.registrarFallos(List.of(new FalloDeEnvio(permanente, "550 no such user", true),
                new FalloDeEnvio(transitoria, "451 try later", false)), MAX, BACKOFF);

        assertThat(estado(permanente)).isEqualTo("FALLIDA");
        assertThat(jdbc.queryForObject("SELECT ultimo_error FROM notificacion_precio WHERE id = ?",
                String.class, permanente)).isEqualTo("550 no such user");
        assertThat(estado(transitoria)).isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("the failure of the last allowed attempt makes the row FALLIDA for good")
    void fallidaAlAgotarIntentos() {
        long id = encolar(usuario, "https://s.test/a", 1000, 800);
        for (int i = 0; i < MAX; i++) {
            jdbc.update("UPDATE notificacion_precio SET proximo_intento_at = now() WHERE id = ?", id);
            assertThat(outbox.reclamar(10, MAX, LEASE)).hasSize(1);
            outbox.registrarFallos(List.of(new FalloDeEnvio(id, "rechazado")), MAX, BACKOFF);
        }

        assertThat(estado(id)).isEqualTo("FALLIDA");
        jdbc.update("UPDATE notificacion_precio SET proximo_intento_at = now() WHERE id = ?", id);
        assertThat(outbox.reclamar(10, MAX, LEASE)).isEmpty();
    }

    @Test
    @DisplayName("rows of a recipient who can no longer be mailed are retired, not returned or retried")
    void destinatarioYaNoElegible() {
        UUID inactivo = nuevoUsuario("inactivo", "inactivo@example.com");
        UUID optOut = nuevoUsuario("optOut", "optout@example.com");
        long delInactivo = encolar(inactivo, "https://s.test/a", 1000, 800);
        long delOptOut = encolar(optOut, "https://s.test/a", 1000, 790);
        long delVigente = encolar(usuario, "https://s.test/a", 1000, 780);
        jdbc.update("UPDATE usuario SET activo = false WHERE id = ?", inactivo);
        jdbc.update("UPDATE usuario SET notificar_bajas_precio = false WHERE id = ?", optOut);

        assertThat(outbox.reclamar(10, MAX, LEASE)).extracting(BajaReclamada::id).containsExactly(delVigente);

        assertThat(estado(delInactivo)).isEqualTo("FALLIDA");
        assertThat(estado(delOptOut)).isEqualTo("FALLIDA");
    }

    @Test
    @DisplayName("retention deletes ENVIADA and FALLIDA rows past the window and keeps everything else")
    void purgaTerminadasViejas() {
        long enviadaVieja = encolar(usuario, "https://s.test/a", 1000, 800);
        long enviadaReciente = encolar(usuario, "https://s.test/b", 1000, 800);
        long fallidaVieja = encolar(usuario, "https://s.test/c", 1000, 800);
        long fallidaReciente = encolar(usuario, "https://s.test/d", 1000, 800);
        long pendienteVieja = encolar(usuario, "https://s.test/e", 1000, 800);
        jdbc.update("""
                UPDATE notificacion_precio SET estado = 'ENVIADA', enviada_at = now() - interval '40 days'
                 WHERE id = ?""", enviadaVieja);
        jdbc.update("""
                UPDATE notificacion_precio SET estado = 'ENVIADA', enviada_at = now() - interval '2 days'
                 WHERE id = ?""", enviadaReciente);
        jdbc.update("""
                UPDATE notificacion_precio SET estado = 'FALLIDA', proximo_intento_at = now() - interval '40 days'
                 WHERE id = ?""", fallidaVieja);
        jdbc.update("""
                UPDATE notificacion_precio SET estado = 'FALLIDA', proximo_intento_at = now() - interval '2 days'
                 WHERE id = ?""", fallidaReciente);
        jdbc.update("UPDATE notificacion_precio SET creada_at = now() - interval '90 days' WHERE id = ?", pendienteVieja);

        int borradas = outbox.purgarTerminadas(Duration.ofDays(30));

        assertThat(borradas).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT id FROM notificacion_precio ORDER BY id", Long.class))
                .containsExactly(enviadaReciente, fallidaReciente, pendienteVieja);
    }

    @Test
    @DisplayName("once purged, the same drop to the same price can be queued again")
    void trasLaPurgaSePuedeVolverAEncolar() {
        long id = encolar(usuario, "https://s.test/a", 1000, 800);
        jdbc.update("UPDATE notificacion_precio SET estado = 'ENVIADA', enviada_at = now() - interval '40 days' WHERE id = ?", id);

        outbox.purgarTerminadas(Duration.ofDays(30));

        assertThat(jdbc.update("""
                INSERT INTO notificacion_precio (usuario_id, url, precio_anterior, precio_nuevo)
                VALUES (?, 'https://s.test/a', 1000, 800) ON CONFLICT ON CONSTRAINT uq_notificacion_precio DO NOTHING
                """, usuario)).isEqualTo(1);
    }

    private void terminadasViejas(int cuantas) {
        jdbc.update("""
                INSERT INTO productos (url, sitio, nombre, precio, rubro, categoria)
                SELECT 'https://purga.test/' || g, 'Mrplacadeco', 'P' || g, 1, 'construccion', 'Placa Decorativa'
                  FROM generate_series(1, ?) g ON CONFLICT (url) DO NOTHING""", cuantas);
        jdbc.update("""
                INSERT INTO notificacion_precio (usuario_id, url, precio_anterior, precio_nuevo, estado, enviada_at, proximo_intento_at)
                SELECT ?, 'https://purga.test/' || g, 2, 1,
                       CASE WHEN g % 2 = 0 THEN 'ENVIADA' ELSE 'FALLIDA' END,
                       CASE WHEN g % 2 = 0 THEN now() - interval '40 days' END,
                       now() - interval '40 days'
                  FROM generate_series(1, ?) g""", usuario, cuantas);
    }

    @Test
    @DisplayName("the purge deletes in bounded chunks, each its own transaction, so it never holds one long lock")
    void purgaPorTrozos() {
        terminadasViejas(25);
        jdbc.execute("CREATE TABLE purga_log (tx bigint)");
        jdbc.execute("""
                CREATE FUNCTION purga_log_fn() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN INSERT INTO purga_log VALUES (txid_current()); RETURN NULL; END $$""");
        jdbc.execute("""
                CREATE TRIGGER purga_log_tr AFTER DELETE ON notificacion_precio
                FOR EACH STATEMENT EXECUTE FUNCTION purga_log_fn()""");
        try {
            int borradas = outbox.purgarTerminadas(Duration.ofDays(30), 10);

            assertThat(borradas).isEqualTo(25);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notificacion_precio", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT tx) FROM purga_log", Integer.class))
                    .as("25 rows in chunks of 10: 10 + 10 + 5").isEqualTo(3);
        } finally {
            jdbc.execute("DROP TRIGGER purga_log_tr ON notificacion_precio");
            jdbc.execute("DROP FUNCTION purga_log_fn()");
            jdbc.execute("DROP TABLE purga_log");
        }
    }

    @Test
    @DisplayName("the chunk's candidate query is served by the retention index, not by a scan of the table")
    void purgaUsaElIndice() {
        terminadasViejas(30_000);
        jdbc.update("INSERT INTO notificacion_precio (usuario_id, url, precio_anterior, precio_nuevo) "
                + "SELECT ?, 'https://purga.test/' || g, 2, 3 FROM generate_series(1, 20) g", usuario);
        jdbc.execute("ANALYZE notificacion_precio");

        String plan = String.join("\n", jdbc.queryForList(
                "EXPLAIN " + NotificacionOutboxRepository.SQL_CANDIDATAS_DE_PURGA
                        .replaceFirst("\\?", "2592000").replaceFirst("\\?", "5000"),
                String.class));

        assertThat(plan).contains("idx_notificacion_precio_terminadas").doesNotContain("Seq Scan");
    }

    private double segundosHastaElProximoIntento(long id) {
        return jdbc.queryForObject("""
                SELECT extract(epoch FROM proximo_intento_at - now()) FROM notificacion_precio WHERE id = ?
                """, Double.class, id);
    }
}
