package ar.scraper.ml;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Admission control for the scoring pipeline ({@link PythonRunner#ejecutar}).
 *
 * <p>Why a reservation at all: {@code ejecutar} resolves THREE fixed paths in
 * the process cwd — {@code ml_productos.json}, {@code ml_output.json} and
 * {@code precio_historico.json} — and hands them to the subprocess as argv.
 * Two concurrent scoring runs therefore write each other's files. The scrape
 * path reaches {@code ejecutar} through {@code ResultAggregator}, and
 * {@code POST /api/ml/aplicar} reached it from an unguarded virtual thread,
 * so "a scrape in flight + an admin clicking aplicar" was enough to corrupt
 * both runs with nothing failing loudly.
 *
 * <p>The reservation mirrors {@code intentarReservarSecuenciaIndiceVisual}
 * (the training slot): CAS, so a burst can never have two winners.
 */
@Epic("ML Pipeline")
@Feature("Python Runner")
@Story("Scoring admission control")
@DisplayName("PythonRunner — one scoring run at a time")
class PythonRunnerScoringGuardTest {

    private final PythonRunner runner = new PythonRunner();

    @Test
    @DisplayName("idle runner runs the body and releases the slot afterwards")
    void runsBodyWhenIdleAndReleases() {
        String out = runner.conReservaDeScoring(() -> "corrió");

        assertThat(out).isEqualTo("corrió");
        assertThat(runner.isScoringEnCurso()).isFalse();
    }

    @Test
    @DisplayName("the slot is held for the duration of the body")
    void slotIsHeldWhileBodyRuns() {
        runner.conReservaDeScoring(() -> {
            assertThat(runner.isScoringEnCurso()).isTrue();
            return null;
        });
    }

    @Test
    @DisplayName("a second run is rejected with null instead of racing the first")
    void rejectsSecondRunWhileFirstIsInFlight() {
        AtomicInteger cuerposEjecutados = new AtomicInteger();

        String out = runner.conReservaDeScoring(() -> {
            cuerposEjecutados.incrementAndGet();
            return runner.conReservaDeScoring(() -> {
                cuerposEjecutados.incrementAndGet();
                return "el segundo no debería correr";
            });
        });

        assertThat(out).isNull();
        assertThat(cuerposEjecutados).hasValue(1);
    }

    @Test
    @DisplayName("a rejected run does NOT release the holder's reservation")
    void rejectedRunLeavesTheHoldersReservationIntact() {
        runner.conReservaDeScoring(() -> {
            runner.conReservaDeScoring(() -> "rechazado");
            // El rechazado no puede liberar una reserva que nunca tomó: si lo
            // hiciera, un tercer llamado entraría en paralelo con este cuerpo,
            // que es exactamente la colisión que el guard existe para cerrar.
            assertThat(runner.isScoringEnCurso()).isTrue();
            return null;
        });
    }

    @Test
    @DisplayName("the slot is released when the body throws")
    void releasesTheSlotWhenBodyThrows() {
        assertThatThrownBy(() -> runner.conReservaDeScoring(() -> {
            throw new IllegalStateException("explotó el pipeline");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(runner.isScoringEnCurso()).isFalse();
    }

    @Test
    @DisplayName("a burst of concurrent calls has exactly one winner")
    void concurrentBurstHasExactlyOneWinner() throws Exception {
        int hilos = 16;
        var largada = new CountDownLatch(1);
        var sueltenElSlot = new CountDownLatch(1);
        var terminaron = new CountDownLatch(hilos);
        AtomicInteger ganadores = new AtomicInteger();

        for (int i = 0; i < hilos; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    largada.await();
                    runner.conReservaDeScoring(() -> {
                        ganadores.incrementAndGet();
                        // Retener el slot hasta que los 16 hayan intentado, para
                        // que el conteo no dependa de que el ganador termine
                        // antes de que el siguiente llegue al CAS.
                        try { sueltenElSlot.await(1, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return null;
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    terminaron.countDown();
                }
            });
        }

        largada.countDown();
        // Dar tiempo a que los 15 perdedores rebote contra el CAS y salgan.
        Thread.sleep(150);
        sueltenElSlot.countDown();

        assertThat(terminaron.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ganadores).hasValue(1);
        assertThat(runner.isScoringEnCurso()).isFalse();
    }
}
