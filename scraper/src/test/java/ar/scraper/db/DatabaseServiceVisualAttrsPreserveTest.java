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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for RELY-001 (4R correction round, fashion-image-classification
 * PR5): {@code upsertProductos}'s {@code ON CONFLICT} clause previously did a blind
 * {@code fit = excluded.fit} (and the other 3 visual columns), so a regular scrape
 * whose ML pass didn't gate a product into the {@code needs_image_fallback} subset
 * (ml_pipeline.py caps that subset at 400 per run) would arrive with blank visual
 * fields and silently wipe out previously-persisted values — including values
 * written by an earlier run or by the backfill CLI ({@code ml_embeddings.py}).
 *
 * <p>The fix: {@code COALESCE(NULLIF(excluded.x,''), x)} per visual column, so a
 * blank incoming value preserves whatever is already in the row.</p>
 *
 * <p>Uses a real (temp-file) SQLite connection via the package-private
 * {@code initEn(path)} test seam, mirroring {@link DatabaseServiceCantidadUnidadesTest}.</p>
 */
@Epic("Persistence")
@Feature("Visual attributes")
@Story("RELY-001: additive-preserve upsert of fit/estampado/escote/color_dominante")
@DisplayName("DatabaseService — visual attrs preserved across blank-visual upserts")
class DatabaseServiceVisualAttrsPreserveTest {

    @TempDir
    Path tempDir;

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        abrirBaseDeDatosTemporal();
    }

    @Step("Open temp-file SQLite DB and initialize schema")
    private void abrirBaseDeDatosTemporal() {
        db = new DatabaseService();
        db.initEn(tempDir.resolve("test-visual-attrs.db").toString());
    }

    @AfterEach
    void tearDown() {
        db.cerrar();
    }

    private Product productoConVisual(String url, Product.VisualAttrs visual) {
        return new Product(
                "Sitio", "Remera con visual", 15000.0, null, url, "http://img.example/x.jpg",
                "Remeras", "unisex", List.of("M", "L"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1, "", visual);
    }

    @Test
    void reUpsertWithBlankVisualAttrsPreservesPreviouslyPersistedValues() {
        Allure.parameter("scenario", "blank-visual re-upsert preserves prior values");
        String url = "https://site.com/visual-preserve";
        Product.VisualAttrs visualInicial =
                new Product.VisualAttrs("oversize", "estampado", "capucha", "gris");

        db.upsertProductos(List.of(productoConVisual(url, visualInicial)));

        // Simulates a later regular scrape whose ML pass did NOT gate this
        // product into the needs_image_fallback subset this run — score
        // entry (and therefore MlEnricher's rebuilt Product) carries blank
        // visual fields.
        db.upsertProductos(List.of(productoConVisual(url, Product.VisualAttrs.EMPTY)));

        List<Product> cargados = db.cargarProductos();
        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).visual()).isEqualTo(visualInicial);

        Optional<Product> obtenido = db.obtenerProducto(url);
        assertThat(obtenido).isPresent();
        assertThat(obtenido.get().visual()).isEqualTo(visualInicial);
    }

    @Test
    void reUpsertWithNonBlankVisualAttrsRefreshesValues() {
        Allure.parameter("scenario", "non-blank visual re-upsert refreshes values");
        String url = "https://site.com/visual-refresh";
        Product.VisualAttrs visualInicial =
                new Product.VisualAttrs("oversize", "estampado", "capucha", "gris");
        Product.VisualAttrs visualNuevo =
                new Product.VisualAttrs("regular", "liso", "con cuello", "azul");

        db.upsertProductos(List.of(productoConVisual(url, visualInicial)));
        db.upsertProductos(List.of(productoConVisual(url, visualNuevo)));

        List<Product> cargados = db.cargarProductos();
        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).visual()).isEqualTo(visualNuevo);
    }

    @Test
    void firstUpsertWithNonBlankVisualAttrsPersistsThem() {
        Allure.parameter("scenario", "first upsert with non-blank visual attrs");
        String url = "https://site.com/visual-first";
        Product.VisualAttrs visual = new Product.VisualAttrs("regular", "liso", "con cuello", "azul");

        db.upsertProductos(List.of(productoConVisual(url, visual)));

        List<Product> cargados = db.cargarProductos();
        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).visual()).isEqualTo(visual);
    }
}
