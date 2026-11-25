package ar.scraper.db;

import ar.scraper.catalog.Normalizacion;
import ar.scraper.catalog.ProductPort;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * manual-classification-lock, Phase 4 (task 4.1 — HIGHEST RISK).
 *
 * <p>Design D5 found two machine write paths that bypass {@code sp_upsert_run}
 * and would otherwise still revert a lock: {@link DatabaseService#actualizarCategoria}
 * (called on every scrape from {@code ResultAggregator.persistirCategoriasRefinadas}
 * and from {@code POST /api/ml/aplicar}) and {@link ProductPort#actualizarNormalizaciones}
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
@Story("Machine write paths (actualizarCategoria/actualizarNormalizaciones) no-op on a locked row")
@DisplayName("DatabaseService — lock guard on machine write paths, human path unaffected")
class DatabaseServiceLockGuardTest extends PostgresTestBase {

    private DatabaseService db;
    private ProductPort productos;

    @BeforeEach
    void setUp() {
        db = TestDatabaseServices.create(dataSource());
        productos = TestDatabaseServices.productos(dataSource());
    }

    private Product producto(String url, String categoria, String marca, String genero) {
        return Product.builder()
                .sitio("freres")
                .nombre("Producto")
                .precio(15000.0)
                .precioOriginal(null)
                .url(url)
                .imagenUrl("http://img.example/x.jpg")
                .categoria(categoria)
                .genero(genero)
                .talles(List.of("M"))
                .ml(Product.MlScore.EMPTY)
                .marca(marca)
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY)
                .build();
    }

    private void lockProduct(String url, String actor) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE productos SET bloqueado_por=?, bloqueado_at=?::timestamptz WHERE url=?")) {
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
        db.upsertProductos(List.of(producto(url, "Remera", "Nike", "hombre")));
        lockProduct(url, "local");

        db.actualizarCategoria(url, "Pantalón");

        Optional<Product> reloaded = db.obtenerProducto(url);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().categoria()).isEqualTo("Remera");
    }

    @Test
    @DisplayName("actualizarCategoria still works on an unlocked product")
    void actualizarCategoriaStillWorksOnUnlockedProduct() throws Exception {
        String url = "https://site.com/cat-unlocked";
        db.upsertProductos(List.of(producto(url, "Remera", "Nike", "hombre")));

        db.actualizarCategoria(url, "Pantalón");

        Optional<Product> reloaded = db.obtenerProducto(url);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().categoria()).isEqualTo("Pantalón");
    }

    // ─── actualizarNormalizaciones (machine path — bulk renormalize) ───────

    private Normalizacion norm(String url, String categoria, String marca, String genero,
                               List<String> talles, String sub) {
        return new Normalizacion(url, categoria, marca, genero, talles, sub, "");
    }

    @Test
    @DisplayName("actualizarNormalizaciones leaves a locked product wholly untouched, sizes included")
    void actualizarNormalizacionesLeavesALockedProductUntouched() throws Exception {
        String url = "https://site.com/norm-locked";
        db.upsertProductos(List.of(producto(url, "Remera", "Nike", "hombre")));
        lockProduct(url, "local");

        Set<String> updated = productos.actualizarNormalizaciones(
                List.of(norm(url, "Pantalón", "Adidas", "mujer", List.of("S", "L"), "trekking")));

        assertThat(updated).isEmpty();
        Product reloaded = db.obtenerProducto(url).orElseThrow();
        assertThat(reloaded.categoria()).isEqualTo("Remera");
        assertThat(reloaded.marca()).isEqualTo("Nike");
        assertThat(reloaded.talles()).containsExactly("M");
    }

    @Test
    @DisplayName("actualizarNormalizaciones updates an unlocked product and reports its url")
    void actualizarNormalizacionesUpdatesAnUnlockedProduct() {
        String url = "https://site.com/norm-unlocked";
        db.upsertProductos(List.of(producto(url, "Remera", "Nike", "hombre")));

        Set<String> updated = productos.actualizarNormalizaciones(
                List.of(norm(url, "Pantalón", "Adidas", "mujer", List.of("S"), "trekking")));

        assertThat(updated).containsExactly(url);
        Product reloaded = db.obtenerProducto(url).orElseThrow();
        assertThat(reloaded.categoria()).isEqualTo("Pantalón");
        assertThat(reloaded.marca()).isEqualTo("Adidas");
    }

    @Test
    @DisplayName("actualizarNormalizaciones skips only the locked row of a mixed batch")
    void actualizarNormalizacionesSkipsOnlyTheLockedRow() throws Exception {
        String libre = "https://site.com/mixed-free";
        String bloqueada = "https://site.com/mixed-locked";
        db.upsertProductos(List.of(producto(libre, "Remera", "Nike", "hombre"),
                producto(bloqueada, "Remera", "Nike", "hombre")));
        lockProduct(bloqueada, "local");

        Set<String> updated = productos.actualizarNormalizaciones(List.of(
                norm(libre, "Pantalón", "Nike", "hombre", List.of("L"), ""),
                norm(bloqueada, "Pantalón", "Nike", "hombre", List.of("L"), "")));

        assertThat(updated).containsExactly(libre);
        assertThat(db.obtenerProducto(libre).orElseThrow().talles()).containsExactly("L");
        assertThat(db.obtenerProducto(bloqueada).orElseThrow().talles()).containsExactly("M");
        assertThat(db.obtenerProducto(bloqueada).orElseThrow().categoria()).isEqualTo("Remera");
    }

    // ─── Human path (aplicarReclasificacionAuditada) — MUST still work on a locked row ──

    @Test
    @DisplayName("human confirmation path still works on an already-locked product (recovery path for a wrong lock)")
    void humanConfirmationPathStillWorksOnAlreadyLockedProduct() throws Exception {
        String url = "https://site.com/human-recovers-lock";
        Product previo = producto(url, "Remera", "Nike", "hombre");
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
