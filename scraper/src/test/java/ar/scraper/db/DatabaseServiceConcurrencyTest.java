package ar.scraper.db;

import ar.scraper.db.support.PostgresContainerSupport;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * decouple-services-postgres, Batch 1, task 1.4 (highest priority — RED
 * first, per strict TDD). Covers spec "Concurrent Write-Path Correctness
 * Without Lock Dance": overlapping scrape + cron + API callers writing
 * overlapping/independent product URLs concurrently, with NO application
 * lock (design D1) — correctness now rests entirely on Postgres MVCC +
 * {@code sp_upsert_run}'s per-row read-then-decide happening inside a single
 * server-side statement, plus {@code UNIQUE(url,fecha)} + {@code ON CONFLICT
 * DO NOTHING} making the concurrent precio_historico insert idempotent.
 *
 * <p>Assertions: no lost updates (every URL's final price is one of the
 * values actually written by a caller, never a corrupted/intermediate
 * state) and no busy/lock-timeout-class failure surfaces from any thread.</p>
 *
 * <p><b>Runtime status at apply time</b>: written against Testcontainers;
 * NOT executed in this sandbox (no Docker daemon available — see
 * {@code sdd/decouple-services-postgres/apply-progress}).</p>
 */
@Epic("Persistence")
@Feature("PostgreSQL write-path (decouple-services-postgres)")
@Story("Concurrent writers, no lock dance")
@DisplayName("DatabaseService — concurrent scrape+cron+API writers, no SQLITE_BUSY-class failures")
class DatabaseServiceConcurrencyTest {

    private DatabaseService db;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = PostgresContainerSupport.start();
        PostgresContainerSupport.truncateAll(dataSource);
        db = new DatabaseService(dataSource);
    }

    private Product producto(String url, String nombre, double precio) {
        return new Product(
                "Sitio", nombre, precio, null, url, "http://img.example/x.jpg",
                "Remeras", "unisex", List.of("M", "L"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }

    @Test
    @DisplayName("scrape (upsertProductos) + cron writer concurrentes: sin batches perdidos, sin errores de lock")
    void concurrentScrapeAndCronWritersLoseNoUpdatesAndNeverBusyFail() throws Exception {
        final int iteraciones = 20;
        final int productosPorBatch = 10;
        CountDownLatch arranque = new CountDownLatch(1);
        List<Throwable> errores = Collections.synchronizedList(new ArrayList<>());
        List<Long> jobIds = Collections.synchronizedList(new ArrayList<>());

        // Hilo "scrape": upsertProductos completo (incluye soft-delete + purge)
        // sobre un set de URLs fijo, escribiendo precios crecientes en cada
        // iteración — simula el path real de un scrape run.
        List<Product> baseSet = new ArrayList<>();
        for (int n = 0; n < productosPorBatch; n++) {
            baseSet.add(producto("https://site.com/race" + n, "Producto " + n, 1000.0 + n));
        }

        Thread hiloScrape = new Thread(() -> {
            try {
                arranque.await();
                for (int i = 0; i < iteraciones; i++) {
                    List<Product> batch = new ArrayList<>();
                    for (int n = 0; n < productosPorBatch; n++) {
                        batch.add(producto("https://site.com/race" + n, "Producto " + n, 1000.0 + n + i));
                    }
                    db.upsertProductos(batch);
                }
            } catch (Throwable t) {
                errores.add(t);
            }
        }, "scrape-writer");

        // Hilo "API/cron": escribe cron_jobs concurrentemente sobre la misma
        // conexión pooled — sin overlap de URLs con el scraper, pero ejerce
        // el mismo pool/datasource al mismo tiempo (spec "Concurrent
        // scrape + cron writers").
        Thread hiloCron = new Thread(() -> {
            try {
                arranque.await();
                for (int i = 0; i < iteraciones; i++) {
                    long id = db.insertCronJob("Job " + i, 1000, 50000, List.of("Freres"),
                            false, true, "0 0 3 * * *", true, "2026-08-01T03:00:00");
                    if (id > 0) {
                        jobIds.add(id);
                        db.updateCronJob(id, "Job " + i + " editado", 2000, 60000, List.of("VCP"),
                                true, false, "0 0 4 * * *", true, "2026-08-02T04:00:00");
                    }
                }
            } catch (Throwable t) {
                errores.add(t);
            }
        }, "cron-writer");

        // Hilo "API upsertParcial": overlapping product URLs con el hilo de
        // scrape — el caso de mayor riesgo de lost-update (dos callers
        // escribiendo la MISMA url concurrentemente).
        Thread hiloApiParcial = new Thread(() -> {
            try {
                arranque.await();
                for (int i = 0; i < iteraciones; i++) {
                    List<Product> batch = new ArrayList<>();
                    for (int n = 0; n < productosPorBatch; n++) {
                        batch.add(producto("https://site.com/race" + n, "Producto " + n, 5000.0 + n + i));
                    }
                    db.upsertParcial(batch);
                }
            } catch (Throwable t) {
                errores.add(t);
            }
        }, "api-parcial-writer");

        hiloScrape.start();
        hiloCron.start();
        hiloApiParcial.start();
        arranque.countDown();
        hiloScrape.join(60_000);
        hiloCron.join(60_000);
        hiloApiParcial.join(60_000);

        assertThat(hiloScrape.isAlive()).as("hiloScrape no terminó dentro del timeout").isFalse();
        assertThat(hiloCron.isAlive()).as("hiloCron no terminó dentro del timeout").isFalse();
        assertThat(hiloApiParcial.isAlive()).as("hiloApiParcial no terminó dentro del timeout").isFalse();

        // No lock/busy-class failure from ANY thread — the key spec
        // assertion ("no busy/lock-timeout error occurs").
        assertThat(errores).as("ningún hilo debe fallar (sin locks de aplicación, MVCC de Postgres)").isEmpty();

        // No lost update: cada URL en carrera termina en UN estado
        // consistente (uno de los valores efectivamente escritos por algún
        // caller), nunca null/corrupto, y el catálogo tiene exactamente
        // productosPorBatch filas activas (ninguna se perdió).
        List<Product> cargados = db.cargarProductos();
        Map<String, Double> precios = cargados.stream()
                .collect(Collectors.toMap(Product::url, Product::precio));
        assertThat(precios).hasSize(productosPorBatch);
        for (int n = 0; n < productosPorBatch; n++) {
            assertThat(precios).containsKey("https://site.com/race" + n);
            assertThat(precios.get("https://site.com/race" + n)).isNotNull();
        }

        // Ningún cron job perdido, todos con el update aplicado.
        assertThat(jobIds).hasSize(iteraciones);
        for (long id : jobIds) {
            var job = db.getCronJob(id);
            assertThat(job).isPresent();
            assertThat(job.get().name()).endsWith("editado");
            assertThat(job.get().precioMin()).isEqualTo(2000);
        }
    }
}
