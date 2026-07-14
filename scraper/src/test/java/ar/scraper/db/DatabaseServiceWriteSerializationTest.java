package ar.scraper.db;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the write-serialization fix (SQLITE_BUSY_SNAPSHOT):
 * la conexión única compartida debe abrirse en modo WAL con busy_timeout,
 * y TODOS los métodos de escritura deben serializar sobre el mismo monitor
 * ({@code writeLock}) — antes solo los métodos de cron sincronizaban entre
 * sí, y escrituras concurrentes (scrape + cron) perdían batches enteros
 * al pisarse commits/rollbacks sobre la misma transacción.
 *
 * <p>Uses a real (temp-file) SQLite connection via the package-private
 * {@code initEn(path)} test seam, mirroring {@link DatabaseServiceCronTest}.</p>
 */
@Epic("Persistence")
@Feature("Write serialization")
@Story("SQLITE_BUSY_SNAPSHOT fix")
@DisplayName("DatabaseService — WAL mode + serialización de escrituras concurrentes")
class DatabaseServiceWriteSerializationTest {

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
        db.initEn(tempDir.resolve("test-write-serialization.db").toString());
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

    // ── Escritor externo (proceso Python) entre lectura y escritura Java ────

    /**
     * Reproduce el disparador ORIGINAL del bug: un proceso externo
     * (ml_embeddings.py) commitea sobre scraper.db entre una lectura Java —
     * que con autoCommit=false deja abierto un snapshot diferido de lectura
     * (WAL) — y la siguiente escritura Java. Sin el rollback inicial de
     * refrescarSnapshot() dentro del bloque de escritura, esa secuencia es la
     * receta exacta de SQLITE_BUSY_SNAPSHOT: la escritura falla, el catch
     * hace rollback y el batch se pierde silenciosamente. Con el fix, el
     * snapshot se refresca y la escritura debe persistir.
     */
    @Test
    void externalCommitBetweenJavaReadAndWriteDoesNotLoseTheWrite() throws Exception {
        String dbPath = tempDir.resolve("test-write-serialization.db").toString();

        // Seed + lectura: cargarProductos() abre un snapshot diferido que queda
        // abierto (autoCommit=false, los métodos de lectura no commitean).
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

    // ── Escrituras concurrentes: scrape (upsertParcial) vs cron ─────────────

    @Test
    void concurrentUpsertParcialAndCronWritesDoNotLoseAnyBatch() throws Exception {
        final int iteraciones = 15;
        final int productosPorBatch = 8;
        CountDownLatch arranque = new CountDownLatch(1);
        List<Throwable> errores = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Long> jobIds = java.util.Collections.synchronizedList(new ArrayList<>());

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
        Map<Long, ar.scraper.cron.CronJob> jobs = db.listCronJobs().stream()
                .collect(Collectors.toMap(ar.scraper.cron.CronJob::id, Function.identity()));
        assertThat(jobs).hasSize(iteraciones);
        for (long id : jobIds) {
            ar.scraper.cron.CronJob job = jobs.get(id);
            assertThat(job).isNotNull();
            assertThat(job.name()).endsWith("editado");
            assertThat(job.precioMin()).isEqualTo(2000);
        }
    }
}
