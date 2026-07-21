package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * decouple-services-postgres, Batch 1 task 1.3 / Batch 4 task 4.6.
 *
 * Covers spec "Data Round-Trip Parity" and "Upsert Semantics Preserved"
 * against a real Postgres instance ({@link PostgresTestBase} — Testcontainers
 * in CI, portable {@code _tools/pgsql} locally), exercising the
 * {@code sp_upsert_run}/{@code sp_soft_delete_ausentes} write path wired in
 * {@link DatabaseService#upsertProductos(List)}.
 */
@Epic("Persistence")
@Feature("PostgreSQL write-path (decouple-services-postgres)")
@Story("Upsert semantics preserved on Postgres")
@DisplayName("DatabaseService — Postgres round-trip + upsert semantics")
class DatabaseServiceTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    private Product producto(String url, String nombre, double precio) {
        return new Product(
                "Sitio", nombre, precio, null, url, "http://img.example/x.jpg",
                "Remeras", "unisex", List.of("M", "L"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }

    // ── Scenario: Full table round-trip ──────────────────────────────────

    @Test
    @DisplayName("producto + historial + favorito + cron_job persisten y se leen field-for-field")
    void fullTableRoundTrip() {
        db.upsertProductos(List.of(producto("https://site.com/rt", "Round Trip", 1999.0)));
        db.guardarFavorito("https://site.com/rt", "Sitio", "Round Trip");
        long jobId = db.insertCronJob("Job RT", 100, 5000, List.of("Sitio"),
                false, true, "0 0 3 * * *", true, "2026-08-01T03:00:00");

        List<Product> cargados = db.cargarProductos();
        assertThat(cargados).hasSize(1);
        Product p = cargados.get(0);
        assertThat(p.url()).isEqualTo("https://site.com/rt");
        assertThat(p.nombre()).isEqualTo("Round Trip");
        assertThat(p.precio()).isEqualTo(1999.0);
        assertThat(p.talles()).containsExactly("M", "L");

        List<Map<String, Object>> hist = db.cargarHistorial("https://site.com/rt");
        assertThat(hist).hasSize(1);
        assertThat(((Number) hist.get(0).get("precio")).doubleValue()).isEqualTo(1999.0);

        assertThat(db.listarFavoritos()).hasSize(1);
        assertThat(jobId).isGreaterThan(0);
        assertThat(db.getCronJob(jobId)).isPresent();
    }

    // ── Scenario: New product ────────────────────────────────────────────

    @Test
    @DisplayName("URL nueva -> INSERT + fila en precio_historico")
    void newProductInsertsAndRecordsHistory() {
        DatabaseService.UpsertStats stats = db.upsertProductos(
                List.of(producto("https://site.com/nuevo", "Nuevo", 500.0)));

        assertThat(stats.nuevos()).isEqualTo(1);
        assertThat(stats.actualizados()).isEqualTo(0);
        assertThat(stats.sinCambios()).isEqualTo(0);
        assertThat(db.cargarHistorial("https://site.com/nuevo")).hasSize(1);
    }

    // ── Scenario: Unchanged price ────────────────────────────────────────

    @Test
    @DisplayName("precio igual -> touched_at only, sin fila nueva en precio_historico")
    void unchangedPriceOnlyTouchesTimestamp() {
        db.upsertProductos(List.of(producto("https://site.com/igual", "Igual", 750.0)));
        DatabaseService.UpsertStats stats = db.upsertProductos(
                List.of(producto("https://site.com/igual", "Igual", 750.0)));

        assertThat(stats.nuevos()).isEqualTo(0);
        assertThat(stats.sinCambios()).isEqualTo(1);
        assertThat(db.cargarHistorial("https://site.com/igual")).hasSize(1);
    }

    // ── Scenario: Changed price ──────────────────────────────────────────

    @Test
    @DisplayName("precio cambio -> UPDATE + nueva fila en precio_historico")
    void changedPriceUpdatesAndRecordsHistory() {
        db.upsertProductos(List.of(producto("https://site.com/cambia", "Cambia", 1000.0)));
        DatabaseService.UpsertStats stats = db.upsertProductos(
                List.of(producto("https://site.com/cambia", "Cambia", 1200.0)));

        assertThat(stats.actualizados()).isEqualTo(1);
        List<Product> cargados = db.cargarProductos();
        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).precio()).isEqualTo(1200.0);
    }

    // ── Scenario: Product missing from run (soft-delete) ─────────────────

    @Test
    @DisplayName("ausente en el run -> soft-delete (activo=0), historial intacto")
    void missingProductIsSoftDeleted() {
        db.upsertProductos(List.of(
                producto("https://site.com/queda", "Queda", 100.0),
                producto("https://site.com/desaparece", "Desaparece", 200.0)));

        DatabaseService.UpsertStats stats = db.upsertProductos(
                List.of(producto("https://site.com/queda", "Queda", 100.0)));

        assertThat(stats.desactivados()).isEqualTo(1);
        assertThat(db.cargarProductos()).extracting(Product::url)
                .containsExactly("https://site.com/queda");
        assertThat(db.esProductoActivo("https://site.com/desaparece")).isFalse();
        // Historial no se toca por el soft-delete
        assertThat(db.cargarHistorial("https://site.com/desaparece")).hasSize(1);
    }

    // ── Scenario: Historial chart on new product (greenfield, no error) ──

    @Test
    @DisplayName("historial de un producto con un solo punto no rompe")
    void singlePointHistoryDoesNotError() {
        db.upsertProductos(List.of(producto("https://site.com/solo", "Solo", 50.0)));
        assertThat(db.cargarHistorial("https://site.com/solo")).hasSize(1);
    }
}
