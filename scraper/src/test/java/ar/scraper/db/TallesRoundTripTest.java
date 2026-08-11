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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * normalize-db-schema-fks-1nf, slice B (V7).
 *
 * <p>Behavioural round-trip of {@code talles} now that it lives in
 * {@code producto_talle} instead of a JSON string column: order survives,
 * a SHRINKING list leaves no stale rows behind (the reason the write is
 * DELETE+INSERT and not {@code ON CONFLICT}, design D4), the read path does
 * NOT filter by {@code activo} (a discontinued product keeps its sizes —
 * {@code obtenerProducto} never filtered it, design D3), and the human
 * classification path still writes sizes on a LOCKED product (the "talles is
 * never locked" invariant, review fix F2 of manual-classification-lock).</p>
 */
@Epic("Persistence")
@Feature("Multi-value normalization")
@Story("talles round-trip through producto_talle — V7")
@DisplayName("V7 — talles round-trip through producto_talle")
class TallesRoundTripTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService(dataSource());
    }

    private Product producto(String url, List<String> talles) {
        return new Product("Sitio", "Producto", 1000.0, null, url, "http://img.example/x.jpg",
                "Remeras", "unisex", talles, Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, 1);
    }

    @Test
    @DisplayName("Sizes round-trip through cargarProductos in their original order")
    void tallesRoundTripInOrder() {
        db.upsertProductos(List.of(producto("https://site.com/talles-orden", List.of("XS", "S", "M", "L"))));

        List<Product> cargados = db.cargarProductos();

        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).talles()).containsExactly("XS", "S", "M", "L");
    }

    @Test
    @DisplayName("A product with no sizes loads as an empty list, not null")
    void productWithoutTallesLoadsEmpty() {
        db.upsertProductos(List.of(producto("https://site.com/talles-vacio", List.of())));

        List<Product> cargados = db.cargarProductos();

        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).talles()).isEmpty();
    }

    @Test
    @DisplayName("Re-upserting a shorter size list leaves no stale sizes behind")
    void shrinkingTheSizeListRemovesStaleRows() {
        String url = "https://site.com/talles-shrink";
        db.upsertProductos(List.of(producto(url, List.of("S", "M", "L"))));

        db.upsertProductos(List.of(producto(url, List.of("S"))));

        List<Product> cargados = db.cargarProductos();
        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).talles()).containsExactly("S");
    }

    @Test
    @DisplayName("A discontinued product keeps its sizes on obtenerProducto")
    void discontinuedProductKeepsItsTalles() {
        String url = "https://site.com/talles-descontinuado";
        db.upsertProductos(List.of(producto(url, List.of("38", "40", "42"))));

        db.marcarDescontinuado(url);

        Optional<Product> encontrado = db.obtenerProducto(url);
        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().talles()).containsExactly("38", "40", "42");
    }

    @Test
    @DisplayName("The machine normalization path replaces the size list")
    void actualizarNormalizacionReplacesTheSizeList() {
        String url = "https://site.com/talles-normalizacion";
        db.upsertProductos(List.of(producto(url, List.of("UNICO"))));

        db.actualizarNormalizacion(url, "Remeras", "Nike", "unisex", List.of("S", "M"), "Basicas");

        Optional<Product> encontrado = db.obtenerProducto(url);
        assertThat(encontrado).isPresent();
        assertThat(encontrado.get().talles()).containsExactly("S", "M");
    }

    @Test
    @DisplayName("upsertParcial writes sizes too — the progressive path is not a second contract")
    void upsertParcialWritesTalles() {
        String url = "https://site.com/talles-parcial";

        db.upsertParcial(List.of(producto(url, List.of("L", "XL"))));

        List<Product> cargados = db.cargarProductos();
        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).talles()).containsExactly("L", "XL");
    }
}
