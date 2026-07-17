package ar.scraper.db;

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
 */
@Epic("Persistence")
@Feature("Favoritos / Sitios dinamicos")
@Story("Null primary-key fail-fast guard")
@DisplayName("DatabaseService — favoritos/sitios null PK guard")
class DatabaseServiceFavoritosSitiosTest {

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
        db.initEn(tempDir.resolve("test-favoritos-sitios.db").toString());
    }

    @AfterEach
    void tearDown() {
        db.cerrar();
    }

    @Test
    void guardarFavoritoWithNullUrlThrowsAndInsertsNothing() {
        assertThatNullPointerException()
                .isThrownBy(() -> db.guardarFavorito(null, "sitio", "nombre"));

        assertThat(db.listarFavoritos()).isEmpty();
    }

    @Test
    void guardarSitioWithNullNombreThrowsAndInsertsNothing() {
        assertThatNullPointerException()
                .isThrownBy(() -> db.guardarSitio(null, "url", "plataforma"));

        assertThat(db.cargarSitiosDinamicos()).isEmpty();
    }

    @Test
    void guardarFavoritoWithValidParamsInsertsNormally() {
        db.guardarFavorito("https://example.com/producto", "Freres", "Producto de prueba");

        assertThat(db.listarFavoritos()).hasSize(1);
        assertThat(db.listarFavoritos().get(0).get("url")).isEqualTo("https://example.com/producto");
        assertThat(db.listarFavoritos().get(0).get("sitio")).isEqualTo("Freres");
        assertThat(db.listarFavoritos().get(0).get("nombre")).isEqualTo("Producto de prueba");
    }

    @Test
    void guardarSitioWithValidParamsInsertsNormally() {
        db.guardarSitio("MiSitio", "https://misitio.com", "shopify");

        assertThat(db.cargarSitiosDinamicos()).hasSize(1);
        assertThat(db.cargarSitiosDinamicos().get(0).get("nombre")).isEqualTo("MiSitio");
        assertThat(db.cargarSitiosDinamicos().get(0).get("url")).isEqualTo("https://misitio.com");
        assertThat(db.cargarSitiosDinamicos().get(0).get("plataforma")).isEqualTo("shopify");
    }
}
