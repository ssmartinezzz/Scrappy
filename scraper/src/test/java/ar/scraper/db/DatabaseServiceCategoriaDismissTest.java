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
        Set<String> dismissed = db.obtenerCategoriaDismiss(yo());

        assertThat(dismissed).isEmpty();
    }

    @Test
    void guardarCategoriaDismissRoundTripsThroughObtener() {
        db.guardarCategoriaDismiss(yo(), "Antiparras");

        Set<String> dismissed = db.obtenerCategoriaDismiss(yo());

        assertThat(dismissed).containsExactly("Antiparras");
    }

    @Test
    void guardarCategoriaDismissIsIdempotentForTheSameCategoria() {
        Allure.parameter("categoria", "Lentes");
        db.guardarCategoriaDismiss(yo(), "Lentes");
        db.guardarCategoriaDismiss(yo(), "Lentes");

        Set<String> dismissed = db.obtenerCategoriaDismiss(yo());

        assertThat(dismissed).containsExactly("Lentes");
    }

    @Test
    void borrarCategoriaDismissRemovesItAndObtenerReturnsEmptyAgain() {
        db.guardarCategoriaDismiss(yo(), "Antiparras");

        db.borrarCategoriaDismiss(yo(), "Antiparras");

        assertThat(db.obtenerCategoriaDismiss(yo())).isEmpty();
    }

    @Test
    void borrarCategoriaDismissOnNonExistentCategoriaIsSafeNoOp() {
        db.guardarCategoriaDismiss(yo(), "Lentes");

        Allure.parameter("categoria", "NoExiste");
        db.borrarCategoriaDismiss(yo(), "NoExiste");

        assertThat(db.obtenerCategoriaDismiss(yo())).containsExactly("Lentes");
    }

    @Test
    void multipleDismissedCategoriasAreAllReturned() {
        db.guardarCategoriaDismiss(yo(), "Antiparras");
        db.guardarCategoriaDismiss(yo(), "Lentes");
        db.guardarCategoriaDismiss(yo(), "Gorras");

        Set<String> dismissed = db.obtenerCategoriaDismiss(yo());

        assertThat(dismissed).containsExactlyInAnyOrder("Antiparras", "Lentes", "Gorras");
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
