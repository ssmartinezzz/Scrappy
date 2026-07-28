package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * manual-classification-lock, Phase 4 (task 4.1 — HIGHEST RISK).
 *
 * <p>Design D5 found two machine write paths that bypass {@code sp_upsert_run}
 * and would otherwise still revert a lock: {@link DatabaseService#actualizarCategoria}
 * (called on every scrape from {@code ResultAggregator.persistirCategoriasRefinadas}
 * and from {@code POST /api/ml/aplicar}) and {@link DatabaseService#actualizarNormalizacion}
 * (called from {@code POST /api/ml/renormalizar}'s bulk path). Both are PUBLIC,
 * MACHINE-facing methods and MUST no-op on a locked row.</p>
 *
 * <p>The guard goes on these two public methods ONLY — NOT on the shared
 * private {@code updateNormalizacion} the human confirmation path
 * ({@link DatabaseService#aplicarReclasificacionAuditada}) also calls.
 * Guarding the shared method would lock the user out of correcting their own
 * mistake: asking the agent again (a second human confirmation) is the
 * chosen recovery path for a wrong lock (non-goal: no unlock UI). This test
 * proves BOTH halves — the machine paths are blocked, and the human path
 * still works on an already-locked product.</p>
 */
@Epic("Persistence")
@Feature("Manual classification lock")
@Story("Machine write paths (actualizarCategoria/actualizarNormalizacion) no-op on a locked row")
@DisplayName("DatabaseService — lock guard on machine write paths, human path unaffected")
class DatabaseServiceLockGuardTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    private Product producto(String url, String categoria, String marca, String genero) {
        return new Product("freres", "Producto", 15000.0, null, url, "http://img.example/x.jpg",
                categoria, genero, List.of("M"), Product.MlScore.EMPTY, marca,
                "indumentaria", false, false, Product.SenalCompra.EMPTY, Product.SenalFinanciacion.EMPTY);
    }

    private void lockProduct(String url, String actor) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE productos SET bloqueado_por=?, bloqueado_at=? WHERE url=?")) {
            ps.setString(1, actor);
            ps.setString(2, "2026-07-28 12:00:00");
            ps.setString(3, url);
            ps.executeUpdate();
        }
    }

    // ─── actualizarCategoria (machine path) ────────────────────────────────

    @Test
    @DisplayName("actualizarCategoria is a no-op on a locked product")
    void actualizarCategoriaNoOpsOnLockedProduct() throws Exception {
        String url = "https://site.com/cat-locked";
        db.upsertProductos(List.of(producto(url, "Remeras", "Nike", "hombre")));
        lockProduct(url, "local");

        db.actualizarCategoria(url, "Pantalones");

        Optional<Product> reloaded = db.obtenerProducto(url);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().categoria()).isEqualTo("Remeras");
    }

    @Test
    @DisplayName("actualizarCategoria still works on an unlocked product")
    void actualizarCategoriaStillWorksOnUnlockedProduct() throws Exception {
        String url = "https://site.com/cat-unlocked";
        db.upsertProductos(List.of(producto(url, "Remeras", "Nike", "hombre")));

        db.actualizarCategoria(url, "Pantalones");

        Optional<Product> reloaded = db.obtenerProducto(url);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().categoria()).isEqualTo("Pantalones");
    }

    // ─── actualizarNormalizacion (machine path — bulk renormalize) ─────────

    @Test
    @DisplayName("actualizarNormalizacion is a no-op (0 rows) on a locked product")
    void actualizarNormalizacionNoOpsOnLockedProduct() throws Exception {
        String url = "https://site.com/norm-locked";
        db.upsertProductos(List.of(producto(url, "Remeras", "Nike", "hombre")));
        lockProduct(url, "local");

        int rows = db.actualizarNormalizacion(url, "Pantalones", "Adidas", "mujer", List.of("S"), "trekking");

        assertThat(rows).isEqualTo(0);
        Optional<Product> reloaded = db.obtenerProducto(url);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().categoria()).isEqualTo("Remeras");
        assertThat(reloaded.get().marca()).isEqualTo("Nike");
    }

    @Test
    @DisplayName("actualizarNormalizacion still works (1 row) on an unlocked product")
    void actualizarNormalizacionStillWorksOnUnlockedProduct() throws Exception {
        String url = "https://site.com/norm-unlocked";
        db.upsertProductos(List.of(producto(url, "Remeras", "Nike", "hombre")));

        int rows = db.actualizarNormalizacion(url, "Pantalones", "Adidas", "mujer", List.of("S"), "trekking");

        assertThat(rows).isEqualTo(1);
        Optional<Product> reloaded = db.obtenerProducto(url);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().categoria()).isEqualTo("Pantalones");
        assertThat(reloaded.get().marca()).isEqualTo("Adidas");
    }

    // ─── Human path (aplicarReclasificacionAuditada) — MUST still work on a locked row ──

    @Test
    @DisplayName("human confirmation path still works on an already-locked product (recovery path for a wrong lock)")
    void humanConfirmationPathStillWorksOnAlreadyLockedProduct() throws Exception {
        String url = "https://site.com/human-recovers-lock";
        Product previo = producto(url, "Remeras", "Nike", "hombre");
        db.upsertProductos(List.of(previo));
        lockProduct(url, "local");

        boolean applied = db.aplicarReclasificacionAuditada(
                url, "Buzo", "Adidas", "mujer", List.of("S"), "urbano", previo, "local");

        assertThat(applied).isTrue();
        Optional<Product> reloaded = db.obtenerProducto(url);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().categoria()).isEqualTo("Buzo");
        assertThat(reloaded.get().marca()).isEqualTo("Adidas");
        assertThat(reloaded.get().genero()).isEqualTo("mujer");
    }
}
