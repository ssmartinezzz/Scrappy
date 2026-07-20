package ar.scraper.db;

import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * badges-oportunidades-revamp — Phase 1: idempotent startup backfill of
 * {@code productos.ml_badge} from the 7 old Spanish snake_case badge keys
 * to the new English keys (spec "ml_badge Migration and Backfill"), plus
 * the comma-delimited multi-badge join/split round-trip (design D1).
 *
 * <p>The backfill runs automatically inside {@link DatabaseService#initEn}
 * (via {@code crearTablas}), so a fresh temp DB is already migrated. To
 * exercise idempotency against a pre-existing legacy row, this test writes
 * directly to the underlying connection (bypassing the Java-side upsert,
 * which already emits new keys) to simulate data left over from before this
 * change, then re-runs the migration explicitly via the package-private
 * {@code backfillBadgeKeys()} seam.</p>
 */
@Epic("Persistence")
@Feature("Badge key migration")
@Story("Idempotent ml_badge backfill")
@DisplayName("DatabaseService — ml_badge old-key backfill + multi-value join/split")
class DatabaseServiceBadgeMigrationTest {

    @TempDir
    Path tempDir;

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService();
        db.initEn(tempDir.resolve("test-badge-migration.db").toString());
    }

    @AfterEach
    void tearDown() {
        db.cerrar();
    }

    private void insertarFilaLegacy(String url, String mlBadgeViejo) throws Exception {
        try (Statement st = db.conexion().createStatement()) {
            st.executeUpdate("""
                INSERT INTO productos (url, sitio, nombre, precio, ml_badge, activo, touched_at, created_at)
                VALUES ('%s', 'Sitio', 'Producto legacy', 1000.0, '%s', 1, '2026-01-01', '2026-01-01')
                """.formatted(url, mlBadgeViejo));
            db.conexion().commit();
        }
    }

    private String leerMlBadge(String url) throws Exception {
        try (Statement st = db.conexion().createStatement();
             var rs = st.executeQuery("SELECT ml_badge FROM productos WHERE url='" + url + "'")) {
            rs.next();
            return rs.getString("ml_badge");
        }
    }

    @Test
    void backfillRewritesAllSevenOldKeysToNewKeys() throws Exception {
        insertarFilaLegacy("https://site.com/a", "precio_historico_bajo");
        insertarFilaLegacy("https://site.com/b", "precio_bajo");
        insertarFilaLegacy("https://site.com/c", "oferta_real");
        insertarFilaLegacy("https://site.com/d", "tendencia");
        insertarFilaLegacy("https://site.com/e", "precio_bajando");
        insertarFilaLegacy("https://site.com/f", "precio_alto");
        insertarFilaLegacy("https://site.com/g", "descuento_cosmetico");

        db.backfillBadgeKeys();

        assertThat(leerMlBadge("https://site.com/a")).isEqualTo("all_time_low");
        assertThat(leerMlBadge("https://site.com/b")).isEqualTo("below_market");
        assertThat(leerMlBadge("https://site.com/c")).isEqualTo("verified_deal");
        assertThat(leerMlBadge("https://site.com/d")).isEqualTo("trending");
        assertThat(leerMlBadge("https://site.com/e")).isEqualTo("price_dropping");
        assertThat(leerMlBadge("https://site.com/f")).isEqualTo("above_market");
        assertThat(leerMlBadge("https://site.com/g")).isEqualTo("fake_discount");
    }

    @Test
    void backfillIsIdempotentOnRerun() throws Exception {
        insertarFilaLegacy("https://site.com/h", "oferta_real");

        db.backfillBadgeKeys();
        assertThat(leerMlBadge("https://site.com/h")).isEqualTo("verified_deal");

        // Re-running must be a no-op: the new key never matches any of the
        // 7 old-key literals in the migration's WHERE clause.
        db.backfillBadgeKeys();
        assertThat(leerMlBadge("https://site.com/h")).isEqualTo("verified_deal");
    }

    @Test
    void backfillLeavesAlreadyNewKeyValuesUntouched() throws Exception {
        insertarFilaLegacy("https://site.com/i", "trending,verified_deal");

        db.backfillBadgeKeys();

        assertThat(leerMlBadge("https://site.com/i")).isEqualTo("trending,verified_deal");
    }

    @Test
    void backfillLeavesEmptyBadgeUntouched() throws Exception {
        insertarFilaLegacy("https://site.com/j", "");

        db.backfillBadgeKeys();

        assertThat(leerMlBadge("https://site.com/j")).isEqualTo("");
    }

    // ── Multi-value ml_badge join/split round-trip (design D1) ──────────────

    private Product productoConBadges(String url, List<String> badges) {
        Product.MlScore ml = new Product.MlScore(
                80, badges, true, "estable", 20, 0.5, "standard");
        return new Product("Sitio", "Producto multi-badge", 15000.0, null, url,
                "http://img.example/x.jpg", "Remeras", "unisex", List.of(), ml, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }

    @Test
    void upsertAndCargarProductosRoundTripsMultiValueBadges() {
        Product p = productoConBadges("https://site.com/multi", List.of("verified_deal", "trending"));

        db.upsertProductos(List.of(p));
        List<Product> cargados = db.cargarProductos();

        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).ml().badges()).containsExactly("verified_deal", "trending");
        assertThat(cargados.get(0).ml().badge()).isEqualTo("verified_deal");
    }

    @Test
    void upsertAndCargarProductosRoundTripsSingleBadge() {
        Product p = productoConBadges("https://site.com/single-badge", List.of("all_time_low"));

        db.upsertProductos(List.of(p));
        List<Product> cargados = db.cargarProductos();

        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).ml().badges()).containsExactly("all_time_low");
    }

    @Test
    void upsertAndCargarProductosRoundTripsNoBadges() {
        Product p = productoConBadges("https://site.com/no-badge", List.of());

        db.upsertProductos(List.of(p));
        List<Product> cargados = db.cargarProductos();

        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).ml().badges()).isEmpty();
        assertThat(cargados.get(0).ml().badge()).isEmpty();
    }
}
