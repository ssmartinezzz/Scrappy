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
class DatabaseServiceOutfitFeedbackEstiloTest extends PostgresTestBase {

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
    void estiloIsPersistedAndReadBack() {
        db.guardarOutfitFeedbackItem(yo(), "hombre", "torso", "https://t/gym", true, "gym");
        db.guardarOutfitFeedbackItem(yo(), "hombre", "torso", "https://t/casual", true, "casual");
        db.guardarOutfitFeedbackItem(yo(), "", "catalog", "https://t/feed", false, "catalog");

        List<DatabaseService.OutfitItemRow> rows = db.obtenerOutfitFeedback(yo());

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
        db.guardarOutfitFeedbackItem(yo(), "hombre", "torso", "https://t/legacy", true);

        List<DatabaseService.OutfitItemRow> rows = db.obtenerOutfitFeedback(yo());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).estilo()).isEqualTo("gym");
    }

    @Test
    void scopedResetClearsOnlyThatEstilo() {
        db.guardarOutfitFeedbackItem(yo(), "hombre", "torso", "https://t/gym",     true, "gym");
        db.guardarOutfitFeedbackItem(yo(), "hombre", "torso", "https://t/casual",  true, "casual");
        db.guardarOutfitFeedbackItem(yo(), "",       "catalog", "https://t/feed",  false, "catalog");

        Allure.parameter("estilo", "gym");
        db.limpiarOutfitFeedback(yo(), "gym");

        List<DatabaseService.OutfitItemRow> rows = db.obtenerOutfitFeedback(yo());
        assertThat(rows).hasSize(2);
        assertThat(rows).noneSatisfy(r -> assertThat(r.estilo()).isEqualTo("gym"));
        assertThat(rows).anySatisfy(r -> assertThat(r.estilo()).isEqualTo("casual"));
        assertThat(rows).anySatisfy(r -> assertThat(r.estilo()).isEqualTo("catalog"));
    }

    @Test
    void scopedResetWithBlankEstiloIsNoOp() {
        db.guardarOutfitFeedbackItem(yo(), "hombre", "torso", "https://t/gym", true, "gym");

        Allure.parameter("estilo", "");
        db.limpiarOutfitFeedback(yo(), "");
        Allure.parameter("estilo", null);
        db.limpiarOutfitFeedback(yo(), null);

        assertThat(db.obtenerOutfitFeedback(yo())).hasSize(1);
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
