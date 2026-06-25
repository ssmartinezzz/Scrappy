package ar.scraper.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseServiceSavedOutfitsTest {

    @TempDir
    Path tempDir;

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService();
        db.initEn(tempDir.resolve("test-saved-outfits.db").toString());
    }

    @AfterEach
    void tearDown() {
        db.cerrar();
    }

    @Test
    void guardarOutfitReturnsPositiveId() {
        int id = db.guardarOutfit("Test", "[{\"slot\":\"torso\"}]", null, 50000.0);

        assertThat(id).isGreaterThan(0);
    }

    @Test
    void guardarOutfitAppearsInObtenerOutfitsGuardados() {
        db.guardarOutfit("Test", "[{\"slot\":\"torso\"}]", null, 50000.0);

        List<Map<String, Object>> list = db.obtenerOutfitsGuardados();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("nombre")).isEqualTo("Test");
        assertThat(list.get(0).get("totalEstimado")).isEqualTo(50000.0);
    }

    @Test
    void obtenerOutfitsGuardadosReturnsMostRecentFirst() throws InterruptedException {
        db.guardarOutfit("Outfit A", "[]", null, 0.0);
        Thread.sleep(1001); // ensure different created_at (second-precision timestamps)
        db.guardarOutfit("Outfit B", "[]", null, 0.0);

        List<Map<String, Object>> list = db.obtenerOutfitsGuardados();

        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("nombre")).isEqualTo("Outfit B");
    }

    @Test
    void eliminarOutfitGuardadoReturnsTrueAndRemovesFromList() {
        int id = db.guardarOutfit("Para borrar", "[]", null, 0.0);

        boolean result = db.eliminarOutfitGuardado(id);

        assertThat(result).isTrue();
        assertThat(db.obtenerOutfitsGuardados()).isEmpty();
    }

    @Test
    void eliminarOutfitGuardadoReturnsFalseForNonExistentId() {
        boolean result = db.eliminarOutfitGuardado(9999);

        assertThat(result).isFalse();
    }

    @Test
    void renombrarOutfitUpdatesNombreInList() {
        int id = db.guardarOutfit("Viejo", "[]", null, 0.0);

        boolean renamed = db.renombrarOutfit(id, "Nuevo");

        assertThat(renamed).isTrue();
        assertThat(db.obtenerOutfitsGuardados().get(0).get("nombre")).isEqualTo("Nuevo");
    }

    @Test
    void renombrarOutfitReturnsFalseForNonExistentId() {
        boolean result = db.renombrarOutfit(9999, "x");

        assertThat(result).isFalse();
    }
}
