package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import ar.scraper.model.Product;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * Unit tests for the null primary-key fail-fast guard added to
 * {@link DatabaseService#guardarFavorito} and {@link DatabaseService#guardarSitio}.
 * {@code favoritos.url} and {@code sitios_dinamicos.nombre} are declared
 * {@code TEXT PRIMARY KEY}, which SQLite does NOT enforce as NOT NULL — a null
 * key would silently insert an untargetable orphan row without this guard.
 * Uses a real (temp-file) SQLite connection via the package-private
 * {@code initEn(path)} test seam, mirroring {@link DatabaseServicePresetTest}.
 *
 * <p>normalize-db-schema-fks-1nf, slice A.1 (V4): {@code favoritos.url} is now
 * FK-bound to {@code productos(url)} (RESTRICT, NOT VALID), so
 * {@code guardarFavoritoWithValidParamsInsertsNormally} must seed the
 * referenced product first — a favourite for a nonexistent product is no
 * longer a valid state.</p>
 */
@Epic("Persistence")
@Feature("Favoritos / Sitios dinamicos")
@Story("Null primary-key fail-fast guard")
@DisplayName("DatabaseService — favoritos/sitios null PK guard")
class DatabaseServiceFavoritosSitiosTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        abrirBaseDeDatosTemporal();
    }

    @Step("Open temp-file SQLite DB and initialize schema")
    private void abrirBaseDeDatosTemporal() {
        db = TestDatabaseServices.create(dataSource());
    }


    @Test
    void guardarFavoritoWithNullUrlThrowsAndInsertsNothing() {
        assertThatNullPointerException()
                .isThrownBy(() -> db.guardarFavorito(yo(), null, "sitio", "nombre"));

        assertThat(db.listarFavoritos(yo())).isEmpty();
    }

    @Test
    void guardarSitioWithNullNombreThrowsAndInsertsNothing() {
        assertThatNullPointerException()
                .isThrownBy(() -> db.guardarSitio(null, "url", "plataforma"));

        assertThat(db.cargarSitiosDinamicos()).isEmpty();
    }

    @Test
    void guardarFavoritoWithValidParamsInsertsNormally() {
        db.upsertProductos(List.of(Product.builder()
                .sitio("Freres")
                .nombre("Producto de prueba")
                .precio(1000.0)
                .precioOriginal(null)
                .url("https://example.com/producto")
                .imagenUrl("http://img.example/x.jpg")
                .categoria("Remera")
                .genero("unisex")
                .talles(List.of())
                .ml(Product.MlScore.EMPTY)
                .marca("Nike")
                .rubro("indumentaria")
                .gymrat(false)
                .marcaPremium(false)
                .senal(Product.SenalCompra.EMPTY)
                .finan(Product.SenalFinanciacion.EMPTY)
                .cantidadUnidades(1)
                .build()));

        db.guardarFavorito(yo(), "https://example.com/producto", "Freres", "Producto de prueba");

        assertThat(db.listarFavoritos(yo())).hasSize(1);
        assertThat(db.listarFavoritos(yo()).get(0).get("url")).isEqualTo("https://example.com/producto");
        assertThat(db.listarFavoritos(yo()).get(0).get("sitio")).isEqualTo("Freres");
        assertThat(db.listarFavoritos(yo()).get(0).get("nombre")).isEqualTo("Producto de prueba");
    }

    @Test
    void guardarSitioWithValidParamsInsertsNormally() {
        db.guardarSitio("MiSitio", "https://misitio.com", "shopify");

        assertThat(db.cargarSitiosDinamicos()).hasSize(1);
        assertThat(db.cargarSitiosDinamicos().get(0).get("nombre")).isEqualTo("MiSitio");
        assertThat(db.cargarSitiosDinamicos().get(0).get("url")).isEqualTo("https://misitio.com");
        assertThat(db.cargarSitiosDinamicos().get(0).get("plataforma")).isEqualTo("shopify");
    }

    /**
     * The owner every personal read and write is scoped by since slice 8.
     *
     * <p>A method rather than a field: {@code PostgresTestBase} truncates between
     * tests, so a cached id would point at a row that no longer exists. Seeding is
     * idempotent, so calling it repeatedly costs three cheap queries and is always
     * correct.</p>
     */
    private UUID yo() {
        return UsuarioDePrueba.yo(dataSource());
    }
}
