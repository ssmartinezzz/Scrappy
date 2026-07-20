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
            java.util.Map<String, String> tipoPorColumna = new java.util.HashMap<>();
            java.util.Map<String, Boolean> notNullPorColumna = new java.util.HashMap<>();
            java.util.Map<String, Boolean> pkPorColumna = new java.util.HashMap<>();
            while (rs.next()) {
                String nombre = rs.getString("name");
                columnas.add(nombre);
                tipoPorColumna.put(nombre, rs.getString("type"));
                notNullPorColumna.put(nombre, rs.getInt("notnull") == 1);
                pkPorColumna.put(nombre, rs.getInt("pk") != 0);
            }
            assertThat(columnas).containsExactlyInAnyOrder(
                    "url", "embedding", "dim", "model_version", "computed_at");

            // Design contract: url is the TEXT primary key; the remaining
            // cache columns are all NOT NULL (a cache row is only ever
            // inserted once the embedding/dim/model_version/computed_at are
            // fully computed — there's no partial/placeholder row shape).
            assertThat(tipoPorColumna.get("url")).isEqualTo("TEXT");
            assertThat(pkPorColumna.get("url")).isTrue();

            assertThat(tipoPorColumna.get("embedding")).isEqualTo("BLOB");
            assertThat(notNullPorColumna.get("embedding")).isTrue();

            assertThat(tipoPorColumna.get("dim")).isEqualTo("INTEGER");
            assertThat(notNullPorColumna.get("dim")).isTrue();

            assertThat(tipoPorColumna.get("model_version")).isEqualTo("TEXT");
            assertThat(notNullPorColumna.get("model_version")).isTrue();

            assertThat(tipoPorColumna.get("computed_at")).isEqualTo("TEXT");
            assertThat(notNullPorColumna.get("computed_at")).isTrue();
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

    /**
     * Simulates a real upgrade from a pre-PR1 database: a {@code productos}
     * table created WITHOUT the {@code fit/estampado/escote/color_dominante}
     * columns (raw SQL, bypassing {@code crearTablas()}), then boots a fresh
     * {@link DatabaseService} against that same file. {@code crearTablas()}'s
     * {@code CREATE TABLE IF NOT EXISTS} is a no-op on the pre-existing table,
     * so the additive columns must come from the {@code migrarColumna}
     * {@code ALTER TABLE ADD COLUMN} calls — this is the path a real upgraded
     * install takes, distinct from the fresh-DB tests above.
     */
    @Test
    void migratesPreExistingProductosTableAndPreservesVisualRoundTrip() throws Exception {
        Path legacyDbPath = tempDir.resolve("test-legacy-upgrade.db");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + legacyDbPath);
             Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE productos (
                    url          TEXT PRIMARY KEY,
                    sitio        TEXT NOT NULL,
                    nombre       TEXT NOT NULL,
                    precio       REAL NOT NULL,
                    precio_orig  TEXT,
                    imagen_url   TEXT,
                    categoria    TEXT,
                    genero       TEXT,
                    talles       TEXT,
                    ml_badge     TEXT DEFAULT '',
                    ml_score     INTEGER DEFAULT 50,
                    ml_oferta    INTEGER DEFAULT 0,
                    ml_tendencia TEXT DEFAULT '',
                    ml_segment   TEXT DEFAULT 'standard',
                    ml_zscore    REAL DEFAULT 0.0,
                    rubro        TEXT DEFAULT 'indumentaria',
                    marca        TEXT DEFAULT '',
                    gymrat       INTEGER DEFAULT 0,
                    marca_premium INTEGER DEFAULT 0,
                    cantidad_unidades INTEGER DEFAULT 1,
                    sub_categoria TEXT DEFAULT '',
                    activo       INTEGER DEFAULT 1,
                    touched_at   TEXT,
                    created_at   TEXT
                )""");
        }

        // Pre-condition sanity check: the legacy shape really is missing the
        // 4 visual columns before DatabaseService touches the file.
        List<String> columnasAntes = new java.util.ArrayList<>();
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + legacyDbPath);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(productos)")) {
            while (rs.next()) columnasAntes.add(rs.getString("name"));
        }
        assertThat(columnasAntes).doesNotContain("fit", "estampado", "escote", "color_dominante");

        DatabaseService dbMigrada = new DatabaseService();
        try {
            dbMigrada.initEn(legacyDbPath.toString());

            List<String> columnasDespues = new java.util.ArrayList<>();
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + legacyDbPath);
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(productos)")) {
                while (rs.next()) columnasDespues.add(rs.getString("name"));
            }
            assertThat(columnasDespues).contains("fit", "estampado", "escote", "color_dominante");

            Product.VisualAttrs visual = new Product.VisualAttrs("oversize", "liso", "en v", "verde");
            Product p = producto("https://site.com/legacy-upgrade", visual);
            dbMigrada.upsertProductos(List.of(p));

            List<Product> cargados = dbMigrada.cargarProductos();
            assertThat(cargados).hasSize(1);
            assertThat(cargados.get(0).visual()).isEqualTo(visual);
        } finally {
            dbMigrada.cerrar();
        }
    }

    // ── contarEmbeddings (T6.3/T6.4 — /api/ml/estado embeddingsCount) ────────

    @Test
    @DisplayName("contarEmbeddings returns 0 on an empty image_embeddings table")
    void contarEmbeddingsReturnsZeroWhenEmpty() {
        assertThat(db.contarEmbeddings()).isZero();
    }

    @Test
    @DisplayName("contarEmbeddings counts every row inserted into image_embeddings")
    void contarEmbeddingsCountsInsertedRows() throws Exception {
        insertarEmbeddingFalso(dbPath, "https://site.com/a.jpg");
        insertarEmbeddingFalso(dbPath, "https://site.com/b.jpg");
        insertarEmbeddingFalso(dbPath, "https://site.com/c.jpg");

        assertThat(db.contarEmbeddings()).isEqualTo(3);
    }

    private void insertarEmbeddingFalso(Path dbFile, String url) throws Exception {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             java.sql.PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO image_embeddings (url, embedding, dim, model_version, computed_at) " +
                     "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, url);
            ps.setBytes(2, new byte[]{1, 2, 3, 4});
            ps.setInt(3, 4);
            ps.setString(4, "marqo-fashionSigLIP-v1");
            ps.setString(5, "2026-01-01T00:00:00Z");
            ps.executeUpdate();
        }
    }
}
