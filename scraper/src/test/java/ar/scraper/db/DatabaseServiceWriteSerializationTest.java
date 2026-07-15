package ar.scraper.db;

import ar.scraper.cron.CronJob;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the write-serialization fix (SQLITE_BUSY_SNAPSHOT) and
 * the dedicated read-connection isolation fix:
 * la conexión única de escritura debe abrirse en modo WAL con busy_timeout,
 * y TODOS los métodos de escritura deben serializar sobre el mismo monitor
 * ({@code writeLock}) — antes solo los métodos de cron sincronizaban entre
 * sí, y escrituras concurrentes (scrape + cron) perdían batches enteros
 * al pisarse commits/rollbacks sobre la misma transacción. Adicionalmente,
 * las lecturas standalone corren sobre una {@code readConn} propia
 * (autoCommit=true) bajo {@code readLock}, aisladas de esa misma carrera.
 *
 * <p>Uses a real (temp-file) SQLite connection via the package-private
 * {@code initEn(path)} test seam, mirroring {@link DatabaseServiceCronTest}.</p>
 */
@Epic("Persistence")
@Feature("Write serialization")
@Story("SQLITE_BUSY_SNAPSHOT fix")
@DisplayName("DatabaseService — WAL mode + serialización de escrituras concurrentes")
class DatabaseServiceWriteSerializationTest {

    private static final String DB_FILE = "test-write-serialization.db";

    @TempDir
    Path tempDir;

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        abrirBaseDeDatosTemporal();
    }

    @Step("Open temp-file SQLite DB and initialize schema")
    private void abrirBaseDeDatosTemporal() {
        db = new DatabaseService();
        db.initEn(tempDir.resolve(DB_FILE).toString());
    }

    @AfterEach
    void tearDown() {
        db.cerrar();
    }

    private Product producto(String url, String nombre, double precio) {
        return new Product(
                "Sitio", nombre, precio, null, url, "http://img.example/x.jpg",
                "Remeras", "unisex", List.of("M", "L"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }

    // ── Pragmas de conexión ──────────────────────────────────────────────────

    @Test
    void connectionIsInWalModeWithBusyTimeoutSet() throws Exception {
        try (Statement st = db.conexion().createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
            }
            try (ResultSet rs = st.executeQuery("PRAGMA busy_timeout")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(30000);
            }
        }
    }

    /**
     * readConn's isolation from the write path rests on two properties that
     * are NOT covered by {@code isValid()}/{@code isNotNull()} alone:
     * autoCommit=true (so every SELECT is its own micro-transaction, never
     * pinning a deferred read snapshot) and busy_timeout=30000 (per-connection
     * — NOT inherited from conn, unlike journal_mode=WAL which IS inherited
     * from the file header). Flipping either back silently reintroduces the
     * original bug while every other assertion in this class stays green.
     */
    @Test
    void readConnectionIsAutoCommitInWalModeWithOwnBusyTimeout() throws Exception {
        assertThat(db.readConexion().getAutoCommit()).isTrue();
        try (Statement st = db.readConexion().createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
            }
            try (ResultSet rs = st.executeQuery("PRAGMA busy_timeout")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(30000);
            }
        }
    }

    // ── Escritor externo (proceso Python) entre lectura y escritura Java ────

    /**
     * Reproduce el disparador ORIGINAL del bug (PR #96): un proceso externo
     * (ml_embeddings.py) commitea sobre scraper.db entre una escritura Java y
     * la siguiente. Históricamente el disparador pasaba por una lectura sobre
     * la conexión de escritura ({@code conn}, autoCommit=false) que dejaba
     * abierto un snapshot diferido; desde la migración a {@code readConn}
     * (dedicated, autoCommit=true) las lecturas standalone como
     * {@code cargarProductos()} ya NO tocan {@code conn} ni abren snapshot
     * ahí — este test ahora cubre directamente que un commit externo entre
     * dos escrituras Java no rompe ni pierde la segunda escritura, que sigue
     * siendo el contrato que {@code refrescarSnapshot()} protege dentro de
     * cada bloque {@code synchronized(writeLock)}.
     */
    @Test
    void externalCommitBetweenJavaReadAndWriteDoesNotLoseTheWrite() throws Exception {
        String dbPath = tempDir.resolve(DB_FILE).toString();

        // Seed: primera escritura Java. La lectura de verificación corre sobre
        // readConn (autoCommit=true) y no deja ningún estado abierto en conn.
        db.upsertParcial(List.of(producto("https://site.com/seed", "Seed", 500.0)));
        assertThat(db.cargarProductos()).hasSize(1);

        // Commit externo desde una conexión independiente en autocommit,
        // haciendo las veces del proceso Python.
        try (Connection externa = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             PreparedStatement ps = externa.prepareStatement(
                     "INSERT OR IGNORE INTO precio_historico (url, precio, fecha) VALUES (?,?,?)")) {
            ps.setString(1, "https://site.com/externo");
            ps.setDouble(2, 999.0);
            ps.setString(3, "2026-01-01");
            ps.executeUpdate();
        }

        // Escritura Java sobre la conexión compartida: debe pasar y persistir.
        db.upsertParcial(List.of(producto("https://site.com/nuevo", "Nuevo", 1234.0)));

        // Verificación desde una tercera conexión fresca (no depende del
        // snapshot de la conexión del servicio).
        try (Connection verif = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             PreparedStatement ps = verif.prepareStatement(
                     "SELECT precio FROM productos WHERE url=? AND activo=1")) {
            ps.setString(1, "https://site.com/nuevo");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble(1)).isEqualTo(1234.0);
            }
        }
    }

    // ── Lifecycle: readConn abre junto a conn y cierra junto a conn ─────────

    @Test
    void initEnOpensBothConnAndReadConnValid() throws Exception {
        assertThat(db.conexion()).isNotNull();
        assertThat(db.conexion().isValid(5)).isTrue();
        assertThat(db.readConexion()).isNotNull();
        assertThat(db.readConexion().isValid(5)).isTrue();
    }

    @Test
    void cerrarClosesBothConnAndReadConn() throws Exception {
        Connection conn = db.conexion();
        Connection readConn = db.readConexion();
        db.cerrar();
        assertThat(conn.isClosed()).isTrue();
        assertThat(readConn.isClosed()).isTrue();
    }

    // ── Lectura concurrente con escritura: no debe ver resultados parciales ─

    /**
     * Reproduce la carrera lectura/escritura sobre la conexión compartida:
     * un hilo escritor inserta productos en batches mientras un hilo lector
     * llama repetidamente a cargarProductos(). Antes de la migración a
     * readConn, el lector compartía `conn` (autoCommit=false) con el
     * escritor, y refrescarSnapshot() (rollback al inicio de cada escritura)
     * podía pisar el snapshot diferido abierto por el SELECT del lector,
     * produciendo una lista parcial/vacía. Con readConn (autoCommit=true,
     * conexión propia) el lector queda aislado de esa carrera.
     */
    @Test
    void concurrentReadDuringWriteNeverReturnsPartialResult() throws Exception {
        final int iteraciones = 30;
        final int productosPorBatch = 5;
        final int totalEsperado = iteraciones * productosPorBatch;
        CountDownLatch arranque = new CountDownLatch(1);
        List<Throwable> errores = Collections.synchronizedList(new ArrayList<>());
        List<Integer> tamanosObservados = Collections.synchronizedList(new ArrayList<>());

        Thread hiloEscritor = new Thread(() -> {
            try {
                arranque.await();
                for (int i = 0; i < iteraciones; i++) {
                    List<Product> batch = new ArrayList<>();
                    for (int j = 0; j < productosPorBatch; j++) {
                        int n = i * productosPorBatch + j;
                        batch.add(producto("https://site.com/race" + n, "Producto " + n, 1000.0 + n));
                    }
                    db.upsertParcial(batch);
                }
            } catch (Throwable t) {
                errores.add(t);
            }
        }, "race-writer");

        Thread hiloLector = new Thread(() -> {
            try {
                arranque.await();
                while (hiloEscritor.isAlive()) {
                    tamanosObservados.add(db.cargarProductos().size());
                }
                // Una lectura final tras el join asegura el tamaño total.
                tamanosObservados.add(db.cargarProductos().size());
            } catch (Throwable t) {
                errores.add(t);
            }
        }, "race-reader");

        hiloEscritor.start();
        hiloLector.start();
        arranque.countDown();
        hiloEscritor.join(30_000);
        hiloLector.join(30_000);

        assertThat(hiloEscritor.isAlive()).as("hiloEscritor no terminó dentro del timeout").isFalse();
        assertThat(hiloLector.isAlive()).as("hiloLector no terminó dentro del timeout").isFalse();
        assertThat(errores).isEmpty();
        assertThat(tamanosObservados).isNotEmpty();
        // Invariante real: ninguna lectura debe retroceder respecto de la
        // anterior. Un rollback de refrescarSnapshot pisando el SELECT del
        // lector se manifestaría como una caída de tamaño entre dos lecturas
        // consecutivas (snapshot truncado) — un simple `<= total` no lo
        // detecta porque cualquier tamaño parcial también cumple esa cota.
        for (int i = 1; i < tamanosObservados.size(); i++) {
            assertThat(tamanosObservados.get(i))
                    .as("lectura #%d retrocedió respecto de la anterior (%d -> %d) — snapshot pisado",
                            i, tamanosObservados.get(i - 1), tamanosObservados.get(i))
                    .isGreaterThanOrEqualTo(tamanosObservados.get(i - 1));
        }
        assertThat(tamanosObservados.get(tamanosObservados.size() - 1)).isEqualTo(totalEsperado);
        assertThat(db.cargarProductos()).hasSize(totalEsperado);
    }

    // ── Escrituras concurrentes: scrape (upsertParcial) vs cron ─────────────

    @Test
    void concurrentUpsertParcialAndCronWritesDoNotLoseAnyBatch() throws Exception {
        final int iteraciones = 15;
        final int productosPorBatch = 8;
        CountDownLatch arranque = new CountDownLatch(1);
        List<Throwable> errores = Collections.synchronizedList(new ArrayList<>());
        List<Long> jobIds = Collections.synchronizedList(new ArrayList<>());

        Thread hiloScrape = new Thread(() -> {
            try {
                arranque.await();
                for (int i = 0; i < iteraciones; i++) {
                    List<Product> batch = new ArrayList<>();
                    for (int j = 0; j < productosPorBatch; j++) {
                        int n = i * productosPorBatch + j;
                        batch.add(producto("https://site.com/p" + n, "Producto " + n, 1000.0 + n));
                    }
                    db.upsertParcial(batch);
                }
            } catch (Throwable t) {
                errores.add(t);
            }
        }, "scrape-writer");

        Thread hiloCron = new Thread(() -> {
            try {
                arranque.await();
                for (int i = 0; i < iteraciones; i++) {
                    long id = db.insertCronJob("Job " + i, 1000, 50000, List.of("Freres"),
                            false, true, "0 0 3 * * *", true, "2026-07-05T03:00:00");
                    if (id > 0) {
                        jobIds.add(id);
                        db.updateCronJob(id, "Job " + i + " editado", 2000, 60000, List.of("VCP"),
                                true, false, "0 0 4 * * *", true, "2026-07-06T04:00:00");
                    }
                }
            } catch (Throwable t) {
                errores.add(t);
            }
        }, "cron-writer");

        hiloScrape.start();
        hiloCron.start();
        arranque.countDown();
        hiloScrape.join(30_000);
        hiloCron.join(30_000);

        assertThat(errores).isEmpty();

        // Ningún batch de productos perdido, con precios correctos
        List<Product> cargados = db.cargarProductos();
        assertThat(cargados).hasSize(iteraciones * productosPorBatch);
        Map<String, Double> precios = cargados.stream()
                .collect(Collectors.toMap(Product::url, Product::precio));
        for (int n = 0; n < iteraciones * productosPorBatch; n++) {
            assertThat(precios.get("https://site.com/p" + n)).isEqualTo(1000.0 + n);
        }

        // Ningún cron job perdido, todos con el update aplicado
        assertThat(jobIds).hasSize(iteraciones);
        Map<Long, CronJob> jobs = db.listCronJobs().stream()
                .collect(Collectors.toMap(CronJob::id, Function.identity()));
        assertThat(jobs).hasSize(iteraciones);
        for (long id : jobIds) {
            CronJob job = jobs.get(id);
            assertThat(job).isNotNull();
            assertThat(job.name()).endsWith("editado");
            assertThat(job.precioMin()).isEqualTo(2000);
        }
    }
}
