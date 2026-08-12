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
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * manual-classification-lock, Phase 2 (task 2.2).
 *
 * <p>Proves the SQL enforcement (V3's {@code sp_upsert_run} CASE guards) end
 * to end against a real PostgreSQL instance: once a product is locked
 * (simulated here via a direct UPDATE, since the human write path that
 * actually sets the lock is Phase 3's job), a re-upsert carrying different
 * title-derived classification values must NOT change {@code categoria},
 * {@code sub_categoria}, {@code marca}, {@code genero} or {@code rubro} —
 * while every volatile column keeps updating, an unlocked product is
 * unaffected, and the lock survives a soft-delete + reactivation cycle.</p>
 */
@Epic("Persistence")
@Feature("Manual classification lock")
@Story("sp_upsert_run CASE guards preserve a locked classification")
@DisplayName("DatabaseService — locked classification survives re-upsert (SQL enforcement)")
class DatabaseServiceLockUpsertTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    private Product producto(String url, String categoria, String subCategoria, String marca,
                              String genero, String rubro, double precio, String nombre) {
        return producto(url, categoria, subCategoria, marca, genero, rubro, precio, nombre,
                List.of("M", "L"));
    }

    /** Overload taking explicit talles, so a test can prove a volatile column actually
     *  changed instead of asserting a value that was already there before the upsert. */
    private Product producto(String url, String categoria, String subCategoria, String marca,
                              String genero, String rubro, double precio, String nombre,
                              List<String> talles) {
        return new Product(
                "Sitio", nombre, precio, null, url, "http://img.example/x.jpg",
                categoria, genero, talles, Product.MlScore.EMPTY, marca,
                rubro, false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1, subCategoria, Product.VisualAttrs.EMPTY);
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

    private boolean isLocked(String url) throws Exception {
        try (Connection c = dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT bloqueado_por FROM productos WHERE url=?")) {
            ps.setString(1, url);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getString(1) != null;
            }
        }
    }

    @Test
    void lockedClassificationSurvivesReUpsertWithDifferentTitleDerivedValues() throws Exception {
        String url = "https://site.com/locked-product";
        db.upsertProductos(List.of(
                producto(url, "Remeras", "running", "Nike", "hombre", "indumentaria", 15000.0, "Remera Nike")));
        lockProduct(url, "local");

        db.upsertProductos(List.of(
                producto(url, "Pantalones", "trekking", "Adidas", "mujer", "tecnologia", 16000.0, "Remera Nike")));

        Optional<Product> reloaded = db.obtenerProducto(url);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().categoria()).isEqualTo("Remeras");
        assertThat(reloaded.get().subCategoria()).isEqualTo("running");
        assertThat(reloaded.get().marca()).isEqualTo("Nike");
        assertThat(reloaded.get().genero()).isEqualTo("hombre");
        assertThat(reloaded.get().rubro()).isEqualTo("indumentaria");
    }

    @Test
    void unlockedProductIsStillOverwrittenOnReUpsert() throws Exception {
        String url = "https://site.com/unlocked-product";
        db.upsertProductos(List.of(
                producto(url, "Remeras", "running", "Nike", "hombre", "indumentaria", 15000.0, "Remera Nike")));

        db.upsertProductos(List.of(
                producto(url, "Pantalones", "trekking", "Adidas", "mujer", "tecnologia", 16000.0, "Remera Nike")));

        Optional<Product> reloaded = db.obtenerProducto(url);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().categoria()).isEqualTo("Pantalones");
        assertThat(reloaded.get().subCategoria()).isEqualTo("trekking");
        assertThat(reloaded.get().marca()).isEqualTo("Adidas");
        assertThat(reloaded.get().genero()).isEqualTo("mujer");
        assertThat(reloaded.get().rubro()).isEqualTo("tecnologia");
    }

    @Test
    void volatileFieldsStillUpdateOnALockedProduct() throws Exception {
        String url = "https://site.com/locked-volatile";
        db.upsertProductos(List.of(
                producto(url, "Remeras", "running", "Nike", "hombre", "indumentaria", 15000.0, "Remera vieja",
                        List.of("M", "L"))));
        lockProduct(url, "local");

        // Every volatile value below differs from the pre-lock one on purpose: asserting a
        // value the row already held would pass even if the column were wrongly CASE-guarded
        // in sp_upsert_run, so the assertion would prove nothing.
        db.upsertProductos(List.of(
                producto(url, "Pantalones", "trekking", "Adidas", "mujer", "tecnologia", 18500.0, "Remera nueva",
                        List.of("S", "XL"))));

        Optional<Product> reloaded = db.obtenerProducto(url);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().nombre()).isEqualTo("Remera nueva");
        assertThat(reloaded.get().precio()).isEqualTo(18500.0);
        assertThat(reloaded.get().talles()).containsExactly("S", "XL");
    }

    @Test
    void lockSurvivesSoftDeleteAndReactivation() throws Exception {
        String url = "https://site.com/locked-reactivated";
        db.upsertProductos(List.of(
                producto(url, "Remeras", "running", "Nike", "hombre", "indumentaria", 15000.0, "Remera Nike")));
        lockProduct(url, "local");

        // Run without this URL present -> soft-deleted (activo=0).
        db.upsertProductos(List.of(
                producto("https://site.com/other-product", "Zapatillas", "", "Puma", "unisex",
                        "indumentaria", 5000.0, "Otra")));

        // Run with a different title-derived classification -> reactivation.
        db.upsertProductos(List.of(
                producto(url, "Pantalones", "trekking", "Adidas", "mujer", "tecnologia", 16000.0, "Remera Nike")));

        Optional<Product> reloaded = db.obtenerProducto(url);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().categoria()).isEqualTo("Remeras");
        assertThat(reloaded.get().subCategoria()).isEqualTo("running");
        assertThat(reloaded.get().marca()).isEqualTo("Nike");
        assertThat(reloaded.get().genero()).isEqualTo("hombre");
        assertThat(reloaded.get().rubro()).isEqualTo("indumentaria");
        assertThat(isLocked(url)).isTrue();
    }
}
