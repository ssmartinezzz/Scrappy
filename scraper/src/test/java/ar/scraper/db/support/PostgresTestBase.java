package ar.scraper.db.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * decouple-services-postgres, Batch 4, task 4.5.
 *
 * <p>Shared Postgres test seam for all DatabaseService-backed test classes.
 * Extend this class to get a ready {@link #dataSource()} with the Flyway
 * baseline applied and all tables truncated before each test.</p>
 *
 * <p><b>Dual mode, auto-selected once per JVM run</b>:</p>
 * <ol>
 *   <li><b>Testcontainers</b> (preferred — CI): used when a Docker daemon is
 *       reachable. Spins a {@code postgres:16-alpine} container.</li>
 *   <li><b>Portable-local</b> (no Docker, e.g. this sandbox / a dev machine
 *       without Docker Desktop): runs {@code initdb} + {@code pg_ctl start}
 *       against the binaries in {@code _tools/pgsql} (the same portable
 *       Postgres the installer provisions — see {@code INSTALAR_Y_CORRER.bat}
 *       / {@code Ejecutar_instalar.sh}) on a fresh temp datadir + an
 *       ephemeral free port, and tears it down via a JVM shutdown hook.</li>
 *   <li><b>Skip</b>: if neither is available, tests extending this class are
 *       skipped via {@link Assumptions#assumeTrue} with a clear message —
 *       this never hard-fails the whole suite.</li>
 * </ol>
 *
 * <p>{@code _tools/pgsql} is located relative to the process's working
 * directory (repo root, since the project is built with
 * {@code mvn -f scraper/pom.xml ...} from the repo root) by walking up
 * parent directories, or overridden via the {@code SCRAPER_TEST_PGSQL_HOME}
 * environment variable.</p>
 */
public abstract class PostgresTestBase {

    private static volatile DataSource sharedDataSource;
    private static volatile String unavailableReason;
    private static final Object LOCK = new Object();

    protected DataSource dataSource() {
        return sharedDataSource;
    }

    @BeforeEach
    void postgresTestBaseSetUp() throws Exception {
        DataSource ds = resolveDataSource();
        truncateAll(ds);
    }

    private static DataSource resolveDataSource() {
        if (sharedDataSource != null) {
            return sharedDataSource;
        }
        synchronized (LOCK) {
            if (sharedDataSource != null) {
                return sharedDataSource;
            }
            if (unavailableReason != null) {
                Assumptions.assumeTrue(false, unavailableReason);
            }
            try {
                sharedDataSource = startTestcontainers();
                return sharedDataSource;
            } catch (Throwable dockerFailure) {
                // Docker not reachable — fall back to portable-local.
            }
            Path portableHome = findPortableHome();
            if (portableHome != null) {
                try {
                    sharedDataSource = startPortableLocal(portableHome);
                    return sharedDataSource;
                } catch (Exception portableFailure) {
                    unavailableReason = "Postgres test seam unavailable: no Docker daemon reachable AND "
                            + "portable-local start failed against " + portableHome + " (" + portableFailure + ")";
                    Assumptions.assumeTrue(false, unavailableReason);
                }
            }
            unavailableReason = "Postgres test seam unavailable: no Docker daemon reachable and no portable "
                    + "Postgres found under _tools/pgsql (run INSTALAR_Y_CORRER.bat / Ejecutar_instalar.sh once, "
                    + "or set SCRAPER_TEST_PGSQL_HOME) — skipping.";
            Assumptions.assumeTrue(false, unavailableReason);
            return null; // unreachable, assumeTrue(false) throws
        }
    }

    // ── Mode 1: Testcontainers ────────────────────────────────────────────

    private static DataSource startTestcontainers() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            throw new IllegalStateException("Docker not available");
        }
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("scraper_test")
                .withUsername("scraper_test")
                .withPassword("scraper_test");
        container.start();

        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(container.getJdbcUrl());
        ds.setUsername(container.getUsername());
        ds.setPassword(container.getPassword());

        migrate(ds);
        return ds;
    }

    // ── Mode 2: portable-local (initdb + pg_ctl against _tools/pgsql) ─────

    private static DataSource startPortableLocal(Path pgsqlHome) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String ext = windows ? ".exe" : "";
        Path bin = pgsqlHome.resolve("bin");
        Path initdb = bin.resolve("initdb" + ext);
        Path pgCtl = bin.resolve("pg_ctl" + ext);
        Path createdb = bin.resolve("createdb" + ext);
        if (!Files.isRegularFile(initdb) || !Files.isRegularFile(pgCtl) || !Files.isRegularFile(createdb)) {
            throw new IllegalStateException("Missing initdb/pg_ctl/createdb under " + bin);
        }

        Path dataDir = Files.createTempDirectory("scraper-test-pgdata-");
        int port = findFreePort();

        run(initdb.toString(), "-D", dataDir.toString(), "-U", "postgres", "-A", "trust",
                "--locale=C", "-E", "UTF8");

        run(pgCtl.toString(), "-D", dataDir.toString(),
                "-o", "-p " + port + " -c listen_addresses=127.0.0.1 -c unix_socket_directories=",
                "-l", dataDir.resolve("server.log").toString(), "-w", "start");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                run(pgCtl.toString(), "-D", dataDir.toString(), "-w", "-m", "fast", "stop");
            } catch (Exception ignored) {
                // best-effort teardown
            }
            deleteRecursive(dataDir);
        }));

        run(createdb.toString(), "-h", "127.0.0.1", "-p", String.valueOf(port), "-U", "postgres",
                "scraper_test");

        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl("jdbc:postgresql://127.0.0.1:" + port + "/scraper_test");
        ds.setUsername("postgres");
        ds.setPassword("");

        migrate(ds);
        return ds;
    }

    private static void run(String... command) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("Timed out running: " + String.join(" ", command));
        }
        if (p.exitValue() != 0) {
            String output = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException("Command failed (" + p.exitValue() + "): "
                    + String.join(" ", command) + "\n" + output);
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static void deleteRecursive(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private static Path findPortableHome() {
        String override = System.getenv("SCRAPER_TEST_PGSQL_HOME");
        if (override != null && Files.isDirectory(Path.of(override))) {
            return Path.of(override);
        }
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("_tools").resolve("pgsql");
            if (Files.isDirectory(candidate.resolve("bin"))) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    // ── Shared helpers ──────────────────────────────────────────────────

    private static void migrate(DataSource ds) {
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /** Wipes all tables between tests — cheap enough with a shared instance. */
    private static void truncateAll(DataSource ds) throws Exception {
        try (var c = ds.getConnection(); var st = c.createStatement()) {
            st.execute("""
                TRUNCATE TABLE
                    precios_externos, productos, image_embeddings, precio_historico,
                    ml_output, sitios_dinamicos, categoria_stats, favoritos,
                    outfit_feedback, outfit_feedback_item, categoria_dismiss,
                    financiacion_presets, saved_outfits, cron_executions, cron_jobs
                RESTART IDENTITY CASCADE
                """);
        }
    }
}
