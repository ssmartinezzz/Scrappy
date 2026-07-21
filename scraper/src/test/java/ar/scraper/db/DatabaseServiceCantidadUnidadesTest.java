package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.model.Product;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for PR2 (pack-pricing-detection propagation): the
 * {@code cantidad_unidades} column must survive the SQLite upsert +
 * hydration round-trip via {@code productoDesdeFila()}. Before this fix,
 * the column did not exist and {@code productoDesdeFila()} rebuilt
 * {@link Product} via the legacy constructor, always resetting the value
 * to 1 on server restart.
 *
 * <p>Uses a real (temp-file) SQLite connection via the package-private
 * {@code initEn(path)} test seam, mirroring {@link DatabaseServicePresetTest}.</p>
 */
@Epic("Persistence")
@Feature("Presets / Pack Pricing / Category Dismiss")
@Story("Pack pricing")
@DisplayName("DatabaseService — cantidadUnidades persistence round-trip")
class DatabaseServiceCantidadUnidadesTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        abrirBaseDeDatosTemporal();
    }

    @Step("Open temp-file SQLite DB and initialize schema")
    private void abrirBaseDeDatosTemporal() {
        db = new DatabaseService(dataSource());
    }


    private Product producto(String url, String nombre, int cantidadUnidades) {
        return new Product(
                "Sitio", nombre, 15000.0, null, url, "http://img.example/x.jpg",
                "Remeras", "unisex", List.of("M", "L"), Product.MlScore.EMPTY, "Nike",
                "indumentaria", false, false, Product.SenalCompra.EMPTY,
                Product.SenalFinanciacion.EMPTY, cantidadUnidades);
    }

    @Test
    void upsertAndCargarProductosPreservesCantidadUnidadesForPack() {
        Allure.parameter("cantidadUnidades", 3);
        Product pack = producto("https://site.com/pack", "Pack x3 Remeras", 3);

        db.upsertProductos(List.of(pack));
        List<Product> cargados = db.cargarProductos();

        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).cantidadUnidades()).isEqualTo(3);
        assertThat(cargados.get(0).esPack()).isTrue();
    }

    @Test
    void upsertAndCargarProductosPreservesCantidadUnidadesForSingleUnit() {
        Allure.parameter("cantidadUnidades", 1);
        Product single = producto("https://site.com/single", "Remera básica", 1);

        db.upsertProductos(List.of(single));
        List<Product> cargados = db.cargarProductos();

        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).cantidadUnidades()).isEqualTo(1);
        assertThat(cargados.get(0).esPack()).isFalse();
    }

    @Test
    void obtenerProductoPreservesCantidadUnidades() {
        Allure.parameter("cantidadUnidades", 2);
        Product pack = producto("https://site.com/combo", "Combo x2", 2);

        db.upsertProductos(List.of(pack));
        Optional<Product> obtenido = db.obtenerProducto("https://site.com/combo");

        assertThat(obtenido).isPresent();
        assertThat(obtenido.get().cantidadUnidades()).isEqualTo(2);
        assertThat(obtenido.get().esPack()).isTrue();
    }

    @Test
    void upsertUpdatesCantidadUnidadesOnReRunWithSameUrl() {
        // Simulates the name being re-detected as a non-pack on a later run.
        Allure.parameter("cantidadUnidades", 4);
        Product packInicial = producto("https://site.com/p1", "Pack x4 Medias", 4);
        db.upsertProductos(List.of(packInicial));

        Allure.parameter("cantidadUnidades", 1);
        Product yaNoPack = producto("https://site.com/p1", "Medias (un par)", 1);
        db.upsertProductos(List.of(yaNoPack));

        List<Product> cargados = db.cargarProductos();
        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).cantidadUnidades()).isEqualTo(1);
    }

    @Test
    void upsertParcialPreservesCantidadUnidades() {
        Allure.parameter("cantidadUnidades", 5);
        Product pack = producto("https://site.com/parcial", "Set x5 Accesorios", 5);

        db.upsertParcial(List.of(pack));
        List<Product> cargados = db.cargarProductos();

        assertThat(cargados).hasSize(1);
        assertThat(cargados.get(0).cantidadUnidades()).isEqualTo(5);
        assertThat(cargados.get(0).esPack()).isTrue();
    }
}
