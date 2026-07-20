package ar.scraper.db.support;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

/**
 * Minimal Testcontainers Postgres seam for Batch 1's write-path tests
 * ({@code DatabaseServiceTest}, {@code DatabaseServiceConcurrencyTest}).
 *
 * <p>This is intentionally NOT the full {@code PostgresTestBase} hybrid seam
 * (CI Testcontainers / local portable {@code _tools/pgsql}, TRUNCATE-based
 * fast isolation for all 66 test classes) — that shared lifecycle
 * infrastructure is explicit Batch 4 scope (task 4.5). This class only
 * starts one shared container, applies the Flyway baseline once, and hands
 * out a pooled-free {@link DataSource} for tests to construct
 * {@code DatabaseService} against.</p>
 *
 * <p><b>Runtime requirement</b>: a working Docker daemon reachable by
 * Testcontainers. In this sandbox no Docker was available at apply time —
 * these tests were written but not executed (see
 * {@code sdd/decouple-services-postgres/apply-progress}).</p>
 */
public final class PostgresContainerSupport {

    private static PostgreSQLContainer<?> container;
    private static DataSource dataSource;

    private PostgresContainerSupport() {}

    public static synchronized DataSource start() {
        if (dataSource != null) return dataSource;

        container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("scraper_test")
                .withUsername("scraper_test")
                .withPassword("scraper_test");
        container.start();

        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(container.getJdbcUrl());
        ds.setUsername(container.getUsername());
        ds.setPassword(container.getPassword());
        dataSource = ds;

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        return dataSource;
    }

    /** Wipes all tables between tests — cheap enough for this small Batch-1 seam. */
    public static void truncateAll(DataSource ds) throws Exception {
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
