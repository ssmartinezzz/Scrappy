package ar.scraper.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code categoria_dismiss} CRUD methods added to
 * {@link DatabaseService} for the personalized-recommendations-feed change
 * (Decision 1 of design.md — dedicated table, no blank-marca sentinel row).
 * Mirrors {@code DatabaseServicePresetTest}'s real temp-file SQLite seam.
 */
class DatabaseServiceCategoriaDismissTest {

    @TempDir
    Path tempDir;

    private DatabaseService db;

    @BeforeEach
    void setUp() {
        db = new DatabaseService();
        db.initEn(tempDir.resolve("test-categoria-dismiss.db").toString());
    }

    @AfterEach
    void tearDown() {
        db.cerrar();
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
