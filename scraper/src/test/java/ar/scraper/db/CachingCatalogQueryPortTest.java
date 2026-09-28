package ar.scraper.db;

import ar.scraper.catalog.CatalogFilter;
import ar.scraper.catalog.CatalogPage;
import ar.scraper.catalog.CatalogQueryPort;
import ar.scraper.catalog.CatalogResumen;
import ar.scraper.catalog.Facets;
import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `catalog-facets-perf`, T3 — cache hit/miss/isolation and single-flight,
 * against a real {@code catalog_version} row (Postgres) and a counting fake
 * delegate (no need to exercise the real GROUPING SETS query here — T1/T2
 * already cover that).
 */
@Epic("Persistence")
@Feature("Catalog query")
@Story("facetas()/resumen() are cached by (cota, catalog_version)")
@DisplayName("CachingCatalogQueryPort — hit/miss/aislamiento/single-flight")
class CachingCatalogQueryPortTest extends PostgresTestBase {

    @Test
    @DisplayName("Una segunda llamada sin escrituras no vuelve a pegarle al delegate")
    void segundaLlamadaEsHit() {
        CountingDelegate delegate = new CountingDelegate();
        CachingCatalogQueryPort cache = new CachingCatalogQueryPort(delegate, dataSource());

        cache.facetas();
        cache.facetas();

        assertThat(delegate.facetasCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Después de una escritura (version bump) la siguiente llamada recalcula")
    void escrituraInvalida() throws Exception {
        CountingDelegate delegate = new CountingDelegate();
        CachingCatalogQueryPort cache = new CachingCatalogQueryPort(delegate, dataSource());

        cache.facetas();
        bumpVersion();
        cache.facetas();

        assertThat(delegate.facetasCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("resumen() y facetas() no comparten entrada, ni dos cotas distintas entre sí")
    void cotasYKindsNoSeMezclan() {
        CountingDelegate delegate = new CountingDelegate();
        CachingCatalogQueryPort cache = new CachingCatalogQueryPort(delegate, dataSource());

        cache.facetas(Optional.empty());
        cache.facetas(Optional.of(Instant.now()));
        cache.resumen(Optional.empty());

        assertThat(delegate.facetasCalls.get()).isEqualTo(2);
        assertThat(delegate.resumenCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Un fallback (facetas vacías) no se cachea: la próxima llamada, misma versión, trae los datos reales")
    void facetasFallbackNoSeCachea() {
        CountingDelegate delegate = new CountingDelegate();
        delegate.facetasFallbackUnaVez = true;
        CachingCatalogQueryPort cache = new CachingCatalogQueryPort(delegate, dataSource());

        Facets primera = cache.facetas();
        Facets segunda = cache.facetas();

        assertThat(primera.marcas()).as("lo que devolvió el delegate la primera vez: el fallback").isEmpty();
        assertThat(segunda.marcas())
                .as("la segunda llamada, MISMA versión, no puede seguir viendo el fallback cacheado")
                .containsKey("Nike");
        assertThat(delegate.facetasCalls.get())
                .as("el fallback no se cacheó: la segunda llamada volvió a pegarle al delegate")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Un fallback (resumen.total()==0) no se cachea: la próxima llamada, misma versión, trae los datos reales")
    void resumenFallbackNoSeCachea() {
        CountingDelegate delegate = new CountingDelegate();
        delegate.resumenFallbackUnaVez = true;
        CachingCatalogQueryPort cache = new CachingCatalogQueryPort(delegate, dataSource());

        CatalogResumen primera = cache.resumen();
        CatalogResumen segunda = cache.resumen();

        assertThat(primera.total()).isZero();
        assertThat(segunda.total()).as("misma versión, no puede seguir viendo el fallback cacheado").isEqualTo(5);
        assertThat(delegate.resumenCalls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("N llamadas concurrentes tras invalidar disparan UN solo recálculo")
    void singleFlightBajoConcurrencia() throws Exception {
        int n = 20;
        CountingDelegate delegate = new CountingDelegate();
        delegate.retrasoMs = 100;
        CachingCatalogQueryPort cache = new CachingCatalogQueryPort(delegate, dataSource());

        CountDownLatch listos = new CountDownLatch(n);
        CountDownLatch arranquen = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    listos.countDown();
                    try {
                        arranquen.await();
                        cache.facetas();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            listos.await();
            arranquen.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(delegate.facetasCalls.get()).isEqualTo(1);
    }

    private void bumpVersion() throws Exception {
        try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE catalog_version SET version = version + 1 WHERE id");
        }
    }

    /**
     * Stand-in delegate: T1/T2 already cover the real SQL, this only counts
     * calls. Returns realistic (non-fallback) data by default — a delegate
     * that always returned the all-empty/total-0 shape would make EVERY test
     * here accidentally exercise the fallback-skip path instead of the
     * ordinary cache path.
     */
    private static class CountingDelegate implements CatalogQueryPort {
        final AtomicInteger facetasCalls = new AtomicInteger();
        final AtomicInteger resumenCalls = new AtomicInteger();
        volatile long retrasoMs = 0;
        volatile boolean facetasFallbackUnaVez = false;
        volatile boolean resumenFallbackUnaVez = false;

        @Override
        public CatalogPage buscar(CatalogFilter filtro, String orden, int page, int size) {
            throw new UnsupportedOperationException("no ejercitado por este test");
        }

        @Override
        public CatalogPage buscar(CatalogFilter filtro, String orden, int page, int size, Optional<Instant> desde) {
            throw new UnsupportedOperationException("no ejercitado por este test");
        }

        @Override
        public Facets facetas() {
            return facetas(Optional.empty());
        }

        @Override
        public Facets facetas(Optional<Instant> desde) {
            facetasCalls.incrementAndGet();
            dormir();
            if (facetasFallbackUnaVez) {
                facetasFallbackUnaVez = false;
                return new Facets(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
            }
            return new Facets(Map.of(), Map.of(), Map.of(), Map.of("Nike", 1L), Map.of(), Map.of());
        }

        @Override
        public CatalogResumen resumen() {
            return resumen(Optional.empty());
        }

        @Override
        public CatalogResumen resumen(Optional<Instant> desde) {
            resumenCalls.incrementAndGet();
            dormir();
            if (resumenFallbackUnaVez) {
                resumenFallbackUnaVez = false;
                return new CatalogResumen(0, 0, Map.of(), Map.of(), 0, 0, 0);
            }
            return new CatalogResumen(1000, 2000, Map.of(), Map.of(), 0, 0, 5);
        }

        private void dormir() {
            if (retrasoMs <= 0) return;
            try {
                Thread.sleep(retrasoMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
