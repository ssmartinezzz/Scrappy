package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR1 (fashion-image-classification): the {@code image_embeddings} cache
 * table and the additive {@code productos} columns (fit/estampado/escote/
 * color_dominante) round-trip through upsert + hydration exactly like
 * {@code cantidad_unidades}/{@code sub_categoria} did in the pack pricing PR.
 *
 * <p>Migrated to {@link PostgresTestBase} per decouple-services-postgres
 * Batch 4 task 4.6. Two scenarios from the original SQLite-era test were
 * RETIRED rather than migrated (no Postgres-relevant analog — see
 * {@code sdd/decouple-services-postgres/apply-progress}):</p>
 * <ul>
 *   <li>{@code crearTablasCreatesImageEmbeddingsTableIdempotently} — relied
 *       on re-running {@code initEn()} against the same SQLite file; the
 *       Postgres schema is Flyway-managed (single versioned migration,
 *       validated on every startup), there is no runtime
 *       {@code crearTablas()}/{@code CREATE TABLE IF NOT EXISTS} idempotency
 *       concern to test anymore.</li>
 *   <li>{@code migratesPreExistingProductosTableAndPreservesVisualRoundTrip}
 *       — simulated a pre-PR1 SQLite install missing the visual columns and
 *       exercised the runtime {@code migrarColumna}/{@code ALTER TABLE ADD
 *       COLUMN} seam. That seam does not exist under Postgres/Flyway — the
 *       baseline migration ships the full schema, including the visual
 *       columns, from day one.</li>
 * </ul>
 * <p>{@code imageEmbeddingsTableHasExpectedColumns} was rewritten to query
 * {@code information_schema.columns} instead of SQLite's
 * {@code PRAGMA table_info}.</p>
 */
@Epic("Persistence")
@Feature("Image Classification Cache")
@Story("image_embeddings table + visual attribute columns")
@DisplayName("DatabaseService — image_embeddings table + VisualAttrs persistence")
class DatabaseServiceImageEmbeddingsTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    private Product producto(String url, Product.VisualAttrs visual) {
        return new Product(
                "Sitio", "Remera básica", 15000.0, null, url, "http://img.example/x.jpg",
                "Remeras", "unisex", List.of("M", "L"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1, "", visual);
    }

    @Test
    void imageEmbeddingsTableHasExpectedColumns() throws Exception {
        try (Connection conn = dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT column_name, data_type, is_nullable FROM information_schema.columns "
                             + "WHERE table_name = 'image_embeddings'");
             ResultSet rs = ps.executeQuery()) {
            List<String> columnas = new java.util.ArrayList<>();
            Map<String, String> tipoPorColumna = new HashMap<>();
            Map<String, Boolean> notNullPorColumna = new HashMap<>();
            while (rs.next()) {
                String nombre = rs.getString("column_name");
                columnas.add(nombre);
                tipoPorColumna.put(nombre, rs.getString("data_type"));
                notNullPorColumna.put(nombre, "NO".equals(rs.getString("is_nullable")));
            }
            assertThat(columnas).containsExactlyInAnyOrder(
                    "url", "embedding", "dim", "model_version", "computed_at");

            assertThat(tipoPorColumna.get("url")).isEqualTo("text");
            assertThat(notNullPorColumna.get("url")).isTrue();

            assertThat(tipoPorColumna.get("embedding")).isEqualTo("bytea");
            assertThat(notNullPorColumna.get("embedding")).isTrue();

            assertThat(tipoPorColumna.get("dim")).isEqualTo("integer");
            assertThat(notNullPorColumna.get("dim")).isTrue();

            assertThat(tipoPorColumna.get("model_version")).isEqualTo("text");
            assertThat(notNullPorColumna.get("model_version")).isTrue();

            assertThat(tipoPorColumna.get("computed_at")).isEqualTo("text");
            assertThat(notNullPorColumna.get("computed_at")).isTrue();
        }
    }

    @Test
    void imageEmbeddingsUrlIsPrimaryKey() throws Exception {
        try (Connection conn = dataSource().getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT kcu.column_name FROM information_schema.table_constraints tc
                     JOIN information_schema.key_column_usage kcu
                       ON tc.constraint_name = kcu.constraint_name
                     WHERE tc.table_name = 'image_embeddings' AND tc.constraint_type = 'PRIMARY KEY'
                     """)) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("column_name")).isEqualTo("url");
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

    // ── contarEmbeddings (T6.3/T6.4 — /api/ml/estado embeddingsCount) ────────

    @Test
    @DisplayName("contarEmbeddings returns 0 on an empty image_embeddings table")
    void contarEmbeddingsReturnsZeroWhenEmpty() {
        assertThat(db.contarEmbeddings()).isZero();
    }

    @Test
    @DisplayName("contarEmbeddings counts every row inserted into image_embeddings")
    void contarEmbeddingsCountsInsertedRows() throws Exception {
        insertarEmbeddingFalso("https://site.com/a.jpg");
        insertarEmbeddingFalso("https://site.com/b.jpg");
        insertarEmbeddingFalso("https://site.com/c.jpg");

        assertThat(db.contarEmbeddings()).isEqualTo(3);
    }

    private void insertarEmbeddingFalso(String url) throws Exception {
        try (Connection conn = dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(
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
