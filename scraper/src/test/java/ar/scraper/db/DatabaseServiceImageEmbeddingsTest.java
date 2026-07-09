package ar.scraper.db;

import ar.scraper.model.Product;
import io.qameta.allure.Allure;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR1 (fashion-image-classification): the new {@code image_embeddings}
 * cache table must be created idempotently by {@code crearTablas()}, and
 * the additive {@code productos} columns (fit/estampado/escote/
 * color_dominante) must round-trip through upsert + hydration exactly
 * like {@code cantidad_unidades}/{@code sub_categoria} did in the pack
 * pricing PR (see {@link DatabaseServiceCantidadUnidadesTest}).
 *
 * <p>This PR is additive/inert only: nothing populates real
 * {@code Product.VisualAttrs} values yet, so round-trip tests exercise the
 * columns via explicit {@code VisualAttrs} construction, not a live model.</p>
 */
@Epic("Persistence")
@Feature("Image Classification Cache")
@Story("image_embeddings table + visual attribute columns")
@DisplayName("DatabaseService — image_embeddings table + VisualAttrs persistence")
class DatabaseServiceImageEmbeddingsTest {

    @TempDir
    Path tempDir;

    private DatabaseService db;
    private Path dbPath;

    @BeforeEach
    void setUp() {
        abrirBaseDeDatosTemporal();
    }

    @Step("Open temp-file SQLite DB and initialize schema")
    private void abrirBaseDeDatosTemporal() {
        db = new DatabaseService();
        dbPath = tempDir.resolve("test-image-embeddings.db");
        db.initEn(dbPath.toString());
    }

    @AfterEach
    void tearDown() {
        db.cerrar();
    }

    private Product producto(String url, Product.VisualAttrs visual) {
        return new Product(
                "Sitio", "Remera básica", 15000.0, null, url, "http://img.example/x.jpg",
                "Remeras", "unisex", List.of("M", "L"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1, "", visual);
    }

    @Test
    void crearTablasCreatesImageEmbeddingsTableIdempotently() throws Exception {
        // crearTablas() already ran once via initEn() in setUp(); re-init on
        // the same file must not fail (CREATE TABLE IF NOT EXISTS re-run safe).
        db.cerrar();
        db = new DatabaseService();
        db.initEn(dbPath.toString());

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name='image_embeddings'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("name")).isEqualTo("image_embeddings");
        }
    }

    @Test
    void imageEmbeddingsTableHasExpectedColumns() throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(image_embeddings)")) {
            List<String> columnas = new java.util.ArrayList<>();
            while (rs.next()) columnas.add(rs.getString("name"));
            assertThat(columnas).containsExactlyInAnyOrder(
                    "url", "embedding", "dim", "model_version", "computed_at");
        }
    }

    @Test
    void upsertAndCargarProductosPreservesVisualAttrs() {
        Product.VisualAttrs visual = new Product.VisualAttrs("oversize", "estampado", "cuello redondo", "azul");
        Allure.parameter("visual", visual);
        Product p = producto("https://site.com/visual", visual);

        db.upsertProductos(List.of(p));
        List<Product> cargados = db.cargarProductos();

        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).visual()).isEqualTo(visual);
    }

    @Test
    void upsertAndCargarProductosDefaultsVisualAttrsToEmptyWhenUnset() {
        Product p = producto("https://site.com/no-visual", Product.VisualAttrs.EMPTY);

        db.upsertProductos(List.of(p));
        List<Product> cargados = db.cargarProductos();

        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).visual()).isEqualTo(Product.VisualAttrs.EMPTY);
    }

    @Test
    void obtenerProductoPreservesVisualAttrs() {
        Product.VisualAttrs visual = new Product.VisualAttrs("regular", "liso", "en v", "negro");
        Product p = producto("https://site.com/obtener-visual", visual);

        db.upsertProductos(List.of(p));
        Optional<Product> obtenido = db.obtenerProducto("https://site.com/obtener-visual");

        assertThat(obtenido).isPresent();
        assertThat(obtenido.get().visual()).isEqualTo(visual);
    }

    @Test
    void upsertUpdatesVisualAttrsOnReRunWithSameUrl() {
        Product.VisualAttrs primero = new Product.VisualAttrs("oversize", "estampado", "cuello redondo", "azul");
        db.upsertProductos(List.of(producto("https://site.com/re-run", primero)));

        Product.VisualAttrs segundo = new Product.VisualAttrs("entallado", "liso", "capucha", "rojo");
        db.upsertProductos(List.of(producto("https://site.com/re-run", segundo)));

        List<Product> cargados = db.cargarProductos();
        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).visual()).isEqualTo(segundo);
    }
}
