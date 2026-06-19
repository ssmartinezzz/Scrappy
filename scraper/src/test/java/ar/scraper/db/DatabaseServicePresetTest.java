package ar.scraper.db;

import ar.scraper.db.DatabaseService.Preset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the financing-preset CRUD methods added to
 * {@link DatabaseService}. Uses a real (temp-file) SQLite connection via the
 * package-private {@code initEn(path)} test seam, since {@code DatabaseService}
 * has no DB abstraction to mock against — mirrors how {@code crearTablas()} is
 * exercised indirectly elsewhere in this codebase (no pure in-memory fakes).
 */
class DatabaseServicePresetTest {

    @TempDir
    Path tempDir;

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService();
        db.initEn(tempDir.resolve("test-financiacion.db").toString());
    }

    @AfterEach
    void tearDown() {
        db.cerrar();
    }

    @Test
    void firstRunSeedsIllustrativeDefaultPresetAsActive() {
        List<Preset> presets = db.listarPresets();

        assertThat(presets).hasSize(1);
        Preset seeded = presets.get(0);
        assertThat(seeded.label()).contains("Ejemplo").contains("editá");
        assertThat(seeded.recargoPct()).isEqualTo(40.0);
        assertThat(seeded.cuotas()).isEqualTo(12);
        assertThat(seeded.activo()).isTrue();

        Optional<Preset> activo = db.cargarPresetActivo();
        assertThat(activo).isPresent();
        assertThat(activo.get().id()).isEqualTo(seeded.id());
    }

    @Test
    void crearPresetAddsNewInactivePreset() {
        int id = db.crearPreset("12 cuotas / 25% recargo", 25.0, 12);

        assertThat(id).isPositive();
        List<Preset> presets = db.listarPresets();
        assertThat(presets).hasSize(2); // seeded default + new one

        Preset created = presets.stream().filter(p -> p.id() == id).findFirst().orElseThrow();
        assertThat(created.label()).isEqualTo("12 cuotas / 25% recargo");
        assertThat(created.recargoPct()).isEqualTo(25.0);
        assertThat(created.cuotas()).isEqualTo(12);
        assertThat(created.activo()).isFalse();
    }

    @Test
    void editarPresetUpdatesFieldsWithoutChangingActiveState() {
        int id = db.crearPreset("Original", 10.0, 6);

        db.editarPreset(id, "Editado", 20.0, 9);

        Preset edited = db.listarPresets().stream()
                .filter(p -> p.id() == id).findFirst().orElseThrow();
        assertThat(edited.label()).isEqualTo("Editado");
        assertThat(edited.recargoPct()).isEqualTo(20.0);
        assertThat(edited.cuotas()).isEqualTo(9);
        assertThat(edited.activo()).isFalse();
    }

    @Test
    void activarPresetDeactivatesPreviouslyActiveOne() {
        Preset original = db.cargarPresetActivo().orElseThrow();
        int nuevoId = db.crearPreset("Preset B", 30.0, 18);

        db.activarPreset(nuevoId);

        Optional<Preset> activo = db.cargarPresetActivo();
        assertThat(activo).isPresent();
        assertThat(activo.get().id()).isEqualTo(nuevoId);

        Preset originalAfter = db.listarPresets().stream()
                .filter(p -> p.id() == original.id()).findFirst().orElseThrow();
        assertThat(originalAfter.activo()).isFalse();

        // Exactly one active preset across the whole table.
        long activeCount = db.listarPresets().stream().filter(Preset::activo).count();
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void eliminarPresetActivoWithOtherPresetsRemainingFallsBackToNoneActive() {
        Preset original = db.cargarPresetActivo().orElseThrow();
        db.crearPreset("Preset B", 30.0, 18);

        db.eliminarPreset(original.id());

        Optional<Preset> activo = db.cargarPresetActivo();
        assertThat(activo).isEmpty();
        assertThat(db.listarPresets()).hasSize(1);
    }

    @Test
    void eliminarPresetActivoAsOnlyRemainingPresetRecreatesIllustrativeDefault() {
        Preset original = db.cargarPresetActivo().orElseThrow();

        db.eliminarPreset(original.id());

        List<Preset> presets = db.listarPresets();
        assertThat(presets).hasSize(1);
        Preset recreated = presets.get(0);
        assertThat(recreated.id()).isNotEqualTo(original.id());
        assertThat(recreated.label()).contains("Ejemplo").contains("editá");
        assertThat(recreated.activo()).isTrue();

        assertThat(db.cargarPresetActivo()).isPresent();
    }

    @Test
    void eliminarPresetWithNonExistentIdAndSingleActivePresetIsSafeNoOp() {
        Preset original = db.cargarPresetActivo().orElseThrow();
        int nonExistentId = original.id() + 999;

        db.eliminarPreset(nonExistentId);

        List<Preset> presets = db.listarPresets();
        assertThat(presets).hasSize(1);

        Preset unchanged = presets.get(0);
        assertThat(unchanged.id()).isEqualTo(original.id());
        assertThat(unchanged.activo()).isTrue();

        long activeCount = presets.stream().filter(Preset::activo).count();
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void eliminarPresetInactivoDoesNotAffectActivePreset() {
        Preset original = db.cargarPresetActivo().orElseThrow();
        int inactiveId = db.crearPreset("Preset inactivo", 15.0, 3);

        db.eliminarPreset(inactiveId);

        Optional<Preset> activo = db.cargarPresetActivo();
        assertThat(activo).isPresent();
        assertThat(activo.get().id()).isEqualTo(original.id());
        assertThat(db.listarPresets()).hasSize(1);
    }

    @Test
    void activarPresetWithNonExistentIdLeavesOriginalActiveAndSignalsFailure() {
        Preset original = db.cargarPresetActivo().orElseThrow();
        int nonExistentId = original.id() + 999;

        boolean result = db.activarPreset(nonExistentId);

        assertThat(result).isFalse();
        Optional<Preset> activo = db.cargarPresetActivo();
        assertThat(activo).isPresent();
        assertThat(activo.get().id()).isEqualTo(original.id());

        long activeCount = db.listarPresets().stream().filter(Preset::activo).count();
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void crearPresetRejectsInvalidCuotasOrRecargoPctWithoutPersisting() {
        int before = db.listarPresets().size();

        assertThat(db.crearPreset("Cuotas cero", 25.0, 0)).isEqualTo(-1);
        assertThat(db.crearPreset("Cuotas negativas", 25.0, -1)).isEqualTo(-1);
        assertThat(db.crearPreset("Recargo invalido", -100.0, 12)).isEqualTo(-1);

        assertThat(db.listarPresets()).hasSize(before);
    }

    @Test
    void editarPresetRejectsInvalidCuotasOrRecargoPctWithoutPersisting() {
        int id = db.crearPreset("Original", 10.0, 6);

        assertThat(db.editarPreset(id, "Editado", 10.0, 0)).isFalse();
        assertThat(db.editarPreset(id, "Editado", 10.0, -1)).isFalse();
        assertThat(db.editarPreset(id, "Editado", -100.0, 6)).isFalse();

        Preset unchanged = db.listarPresets().stream()
                .filter(p -> p.id() == id).findFirst().orElseThrow();
        assertThat(unchanged.label()).isEqualTo("Original");
        assertThat(unchanged.recargoPct()).isEqualTo(10.0);
        assertThat(unchanged.cuotas()).isEqualTo(6);
    }

    @Test
    void editarPresetWithNonExistentIdSignalsFailureAndLeavesExistingPresetUnchanged() {
        Preset original = db.cargarPresetActivo().orElseThrow();
        int nonExistentId = original.id() + 999;

        boolean result = db.editarPreset(nonExistentId, "x", 10.0, 5);

        assertThat(result).isFalse();
        Preset unchanged = db.listarPresets().stream()
                .filter(p -> p.id() == original.id()).findFirst().orElseThrow();
        assertThat(unchanged.label()).isEqualTo(original.label());
        assertThat(unchanged.recargoPct()).isEqualTo(original.recargoPct());
        assertThat(unchanged.cuotas()).isEqualTo(original.cuotas());
    }

    @Test
    void listarPresetsOrdersByIdWhenCreatedAtCollides() {
        // Inserts in quick succession share the same second-precision created_at,
        // so the secondary `id` sort key is what guarantees deterministic order.
        int idA = db.crearPreset("Preset A", 10.0, 3);
        int idB = db.crearPreset("Preset B", 20.0, 6);
        int idC = db.crearPreset("Preset C", 30.0, 9);

        List<Preset> presets = db.listarPresets();
        List<Integer> ids = presets.stream().map(Preset::id).toList();

        int posA = ids.indexOf(idA);
        int posB = ids.indexOf(idB);
        int posC = ids.indexOf(idC);

        assertThat(posA).isLessThan(posB);
        assertThat(posB).isLessThan(posC);
    }
}
