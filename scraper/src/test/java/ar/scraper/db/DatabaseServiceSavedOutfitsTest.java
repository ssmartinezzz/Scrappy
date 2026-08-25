package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import ar.scraper.db.support.UsuarioDePrueba;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Persistence")
@Feature("Outfit Feedback / Saved Outfits")
@Story("Saved outfits")
@DisplayName("DatabaseService — saved outfits CRUD")
class DatabaseServiceSavedOutfitsTest extends PostgresTestBase {

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        abrirBaseDeDatosTemporal();
    }

    @Step("Open temp-file SQLite DB and initialize schema")
    private void abrirBaseDeDatosTemporal() {
        db = new DatabaseService(dataSource());
    }


    @Test
    void guardarOutfitReturnsPositiveId() {
        int id = db.guardarOutfit(yo(), "Test", "[{\"slot\":\"torso\"}]", null, 50000.0);

        assertThat(id).isGreaterThan(0);
    }

    @Test
    void guardarOutfitAppearsInObtenerOutfitsGuardados() {
        db.guardarOutfit(yo(), "Test", "[{\"slot\":\"torso\"}]", null, 50000.0);

        List<Map<String, Object>> list = db.obtenerOutfitsGuardados(yo());

        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("nombre")).isEqualTo("Test");
        assertThat(list.get(0).get("totalEstimado")).isEqualTo(50000.0);
    }

    @Test
    void obtenerOutfitsGuardadosReturnsMostRecentFirst() throws InterruptedException {
        db.guardarOutfit(yo(), "Outfit A", "[]", null, 0.0);
        Thread.sleep(1001); // ensure different created_at (second-precision timestamps)
        db.guardarOutfit(yo(), "Outfit B", "[]", null, 0.0);

        List<Map<String, Object>> list = db.obtenerOutfitsGuardados(yo());

        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("nombre")).isEqualTo("Outfit B");
    }

    @Test
    void eliminarOutfitGuardadoReturnsTrueAndRemovesFromList() {
        int id = db.guardarOutfit(yo(), "Para borrar", "[]", null, 0.0);

        boolean result = db.eliminarOutfitGuardado(yo(), id);

        assertThat(result).isTrue();
        assertThat(db.obtenerOutfitsGuardados(yo())).isEmpty();
    }

    @Test
    void eliminarOutfitGuardadoReturnsFalseForNonExistentId() {
        Allure.parameter("id", 9999);
        boolean result = db.eliminarOutfitGuardado(yo(), 9999);

        assertThat(result).isFalse();
    }

    @Test
    void renombrarOutfitUpdatesNombreInList() {
        int id = db.guardarOutfit(yo(), "Viejo", "[]", null, 0.0);

        boolean renamed = db.renombrarOutfit(yo(), id, "Nuevo");

        assertThat(renamed).isTrue();
        assertThat(db.obtenerOutfitsGuardados(yo()).get(0).get("nombre")).isEqualTo("Nuevo");
    }

    @Test
    void renombrarOutfitReturnsFalseForNonExistentId() {
        Allure.parameter("id", 9999);
        boolean result = db.renombrarOutfit(yo(), 9999, "x");

        assertThat(result).isFalse();
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
