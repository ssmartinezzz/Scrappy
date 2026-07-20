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
 * Confirms {@code outfit_feedback_item} read/write is slot-agnostic — the
 * shared taste signal store (spec.md "Shared Taste Signal Across Surfaces")
 * must accept and return rows regardless of {@code slot} value, including
 * the new {@code slot="catalog"} sentinel used by the recommendations feed's
 * per-card like/dislike (design.md Decision 2). No schema change is needed:
 * {@code slot} is already a free-text column.
 */
@Epic("Persistence")
@Feature("Outfit Feedback / Saved Outfits")
@Story("Feedback slot-agnostic")
@DisplayName("DatabaseService — outfit feedback slot-agnostic")
class DatabaseServiceOutfitFeedbackSlotAgnosticTest {

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
        db.initEn(tempDir.resolve("test-slot-agnostic.db").toString());
    }

    @AfterEach
    void tearDown() {
        db.cerrar();
    }

    @Test
    void catalogSlotRowsAreAcceptedAndReadBackAlongsideOutfitBuilderSlots() {
        Allure.parameter("slot", "torso");
        db.guardarOutfitFeedbackItem("hombre", "torso", "https://site/torso-item", true);
        Allure.parameter("slot", "catalog");
        db.guardarOutfitFeedbackItem("", "catalog", "https://site/catalog-item", false);

        List<DatabaseService.OutfitItemRow> rows = db.obtenerOutfitFeedback();

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.slot()).isEqualTo("torso");
            assertThat(r.url()).isEqualTo("https://site/torso-item");
            assertThat(r.liked()).isTrue();
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.slot()).isEqualTo("catalog");
            assertThat(r.url()).isEqualTo("https://site/catalog-item");
            assertThat(r.liked()).isFalse();
        });
    }
}
