package ar.scraper.db;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms {@code outfit_feedback_item} persists and returns the {@code estilo}
 * dimension, and that {@link DatabaseService#limpiarOutfitFeedback(String)}
 * clears only the requested estilo's rows (per-surface reset).
 */
@Epic("Persistence")
@Feature("Outfit Feedback / Saved Outfits")
@Story("Feedback estilo")
@DisplayName("DatabaseService — outfit feedback estilo dimension")
class DatabaseServiceOutfitFeedbackEstiloTest {

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
        db.initEn(tempDir.resolve("test-estilo.db").toString());
    }

    @AfterEach
    void tearDown() {
        db.cerrar();
    }

    @Test
    void estiloIsPersistedAndReadBack() {
        db.guardarOutfitFeedbackItem("hombre", "torso", "https://t/gym", true, "gym");
        db.guardarOutfitFeedbackItem("hombre", "torso", "https://t/casual", true, "casual");
        db.guardarOutfitFeedbackItem("", "catalog", "https://t/feed", false, "catalog");

        List<DatabaseService.OutfitItemRow> rows = db.obtenerOutfitFeedback();

        assertThat(rows).hasSize(3);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.url()).isEqualTo("https://t/gym");
            assertThat(r.estilo()).isEqualTo("gym");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.url()).isEqualTo("https://t/casual");
            assertThat(r.estilo()).isEqualTo("casual");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.url()).isEqualTo("https://t/feed");
            assertThat(r.estilo()).isEqualTo("catalog");
        });
    }

    @Test
    void legacyOverloadDefaultsToGym() {
        db.guardarOutfitFeedbackItem("hombre", "torso", "https://t/legacy", true);

        List<DatabaseService.OutfitItemRow> rows = db.obtenerOutfitFeedback();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).estilo()).isEqualTo("gym");
    }

    @Test
    void scopedResetClearsOnlyThatEstilo() {
        db.guardarOutfitFeedbackItem("hombre", "torso", "https://t/gym",     true, "gym");
        db.guardarOutfitFeedbackItem("hombre", "torso", "https://t/casual",  true, "casual");
        db.guardarOutfitFeedbackItem("",       "catalog", "https://t/feed",  false, "catalog");

        Allure.parameter("estilo", "gym");
        db.limpiarOutfitFeedback("gym");

        List<DatabaseService.OutfitItemRow> rows = db.obtenerOutfitFeedback();
        assertThat(rows).hasSize(2);
        assertThat(rows).noneSatisfy(r -> assertThat(r.estilo()).isEqualTo("gym"));
        assertThat(rows).anySatisfy(r -> assertThat(r.estilo()).isEqualTo("casual"));
        assertThat(rows).anySatisfy(r -> assertThat(r.estilo()).isEqualTo("catalog"));
    }

    @Test
    void scopedResetWithBlankEstiloIsNoOp() {
        db.guardarOutfitFeedbackItem("hombre", "torso", "https://t/gym", true, "gym");

        Allure.parameter("estilo", "");
        db.limpiarOutfitFeedback("");
        Allure.parameter("estilo", null);
        db.limpiarOutfitFeedback(null);

        assertThat(db.obtenerOutfitFeedback()).hasSize(1);
    }
}
