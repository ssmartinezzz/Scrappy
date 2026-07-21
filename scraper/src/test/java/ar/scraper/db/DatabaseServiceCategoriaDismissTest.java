package ar.scraper.db;

import ar.scraper.db.support.PostgresTestBase;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Step;
import io.qameta.allure.Story;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code categoria_dismiss} CRUD methods added to
 * {@link DatabaseService} for the personalized-recommendations-feed change
 * (Decision 1 of design.md — dedicated table, no blank-marca sentinel row).
 * Mirrors {@code DatabaseServicePresetTest}'s real temp-file SQLite seam.
 */
@Epic("Persistence")
@Feature("Presets / Pack Pricing / Category Dismiss")
@Story("Category dismiss")
@DisplayName("DatabaseService — categoria dismiss CRUD")
class DatabaseServiceCategoriaDismissTest extends PostgresTestBase {

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
    void obtenerCategoriaDismissIsEmptyWhenNothingDismissedYet() {
        Set<String> dismissed = db.obtenerCategoriaDismiss();

        assertThat(dismissed).isEmpty();
    }

    @Test
    void guardarCategoriaDismissRoundTripsThroughObtener() {
        db.guardarCategoriaDismiss("Antiparras");

        Set<String> dismissed = db.obtenerCategoriaDismiss();

        assertThat(dismissed).containsExactly("Antiparras");
    }

    @Test
    void guardarCategoriaDismissIsIdempotentForTheSameCategoria() {
        Allure.parameter("categoria", "Lentes");
        db.guardarCategoriaDismiss("Lentes");
        db.guardarCategoriaDismiss("Lentes");

        Set<String> dismissed = db.obtenerCategoriaDismiss();

        assertThat(dismissed).containsExactly("Lentes");
    }

    @Test
    void borrarCategoriaDismissRemovesItAndObtenerReturnsEmptyAgain() {
        db.guardarCategoriaDismiss("Antiparras");

        db.borrarCategoriaDismiss("Antiparras");

        assertThat(db.obtenerCategoriaDismiss()).isEmpty();
    }

    @Test
    void borrarCategoriaDismissOnNonExistentCategoriaIsSafeNoOp() {
        db.guardarCategoriaDismiss("Lentes");

        Allure.parameter("categoria", "NoExiste");
        db.borrarCategoriaDismiss("NoExiste");

        assertThat(db.obtenerCategoriaDismiss()).containsExactly("Lentes");
    }

    @Test
    void multipleDismissedCategoriasAreAllReturned() {
        db.guardarCategoriaDismiss("Antiparras");
        db.guardarCategoriaDismiss("Lentes");
        db.guardarCategoriaDismiss("Gorras");

        Set<String> dismissed = db.obtenerCategoriaDismiss();

        assertThat(dismissed).containsExactlyInAnyOrder("Antiparras", "Lentes", "Gorras");
    }
}
