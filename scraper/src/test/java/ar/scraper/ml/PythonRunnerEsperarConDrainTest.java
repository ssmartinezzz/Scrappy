package ar.scraper.ml;

import ar.scraper.ml.PythonRunner.ResultadoEspera;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for RESI-001: {@code PythonRunner#esperarConDrain}, the
 * bounded-read/timeout seam backing both sequenced phase methods
 * ({@code ejecutarFaseEntrenamientoSecuenciada}/{@code ejecutarFaseBackfillSecuenciada}).
 *
 * <p>Unlike the rest of the {@code PythonRunner} test family (which never
 * spawns a real process — see {@link PythonRunnerGpuFlagTest}'s javadoc),
 * this class deliberately spawns REAL, short-lived {@code cmd.exe} child
 * processes: the bug this fixes ("stdout read to EOF with a blocking
 * {@code readLine()} loop BEFORE {@code proc.waitFor(timeout)} is ever
 * reached") can only be demonstrated with a live process whose stdout stays
 * open-but-silent, which a never-started {@code ProcessBuilder} assertion
 * cannot exercise. No Python interpreter is required — {@code cmd.exe} is
 * always present on Windows CI/dev machines. The {@code cmd.exe} dependency
 * makes this class Windows-only; the product itself ships as a Windows
 * installer, so Linux CI skipping these does not reduce shipped coverage.</p>
 */
@Epic("ML Pipeline")
@Feature("Python Runner")
@Story("Bounded subprocess read + timeout (RESI-001)")
@DisplayName("PythonRunner — esperarConDrain bounded read/timeout")
@EnabledOnOs(OS.WINDOWS)
class PythonRunnerEsperarConDrainTest {

    private final PythonRunner runner = new PythonRunner();

    @Test
    @DisplayName("times out promptly when the child stays alive with silent stdout, instead of blocking forever")
    void timesOutPromptlyWhenChildStaysAliveWithSilentStdout() throws Exception {
        // Stays alive ~4-5s (5 pings, ~1s apart) with its own stdout fully
        // redirected to NUL — Java's Process sees an open stdout stream that
        // never produces a byte and never closes until the child exits. This
        // is exactly the "cold model-weight download that emits no progress"
        // shape RESI-001 fixes: with the OLD code (blocking readLine() loop
        // BEFORE waitFor), a 300ms timeout would never even be evaluated —
        // the calling thread would block for the full ~4-5s ping duration
        // (or forever, for a truly silent+never-exiting child).
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ping -n 5 127.0.0.1 >NUL");
        Process proc = pb.start();

        long start = System.nanoTime();
        ResultadoEspera resultado = runner.esperarConDrain(
                proc, 300, TimeUnit.MILLISECONDS, "training", null, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(resultado.finished()).isFalse();
        assertThat(resultado.exitCode()).isEqualTo(-1);
        // Must return well before the ping's ~4-5s natural completion — proves
        // waitFor(timeout) was actually reached and evaluated, not starved
        // behind a blocking stdout read.
        assertThat(elapsedMs).isLessThan(3000);
        assertThat(proc.isAlive()).isFalse(); // destroyForcibly() was called
    }

    @Test
    @DisplayName("returns exit code and drains both streams when the process finishes normally")
    void returnsExitCodeAndDrainsStreamsWhenProcessFinishesNormally() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "echo out-line 1>&2 & exit /b 0");
        Process proc = pb.start();

        List<String> stderrLines = new CopyOnWriteArrayList<>();
        List<String> stdoutLines = new CopyOnWriteArrayList<>();
        ResultadoEspera resultado = runner.esperarConDrain(
                proc, 10, TimeUnit.SECONDS, "training", stdoutLines::add, stderrLines::add);

        assertThat(resultado.finished()).isTrue();
        assertThat(resultado.exitCode()).isZero();
        assertThat(stderrLines).anySatisfy(l -> assertThat(l).contains("out-line"));
    }

    @Test
    @DisplayName("non-zero exit code is surfaced when the process finishes")
    void surfacesNonZeroExitCode() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "exit /b 3");
        Process proc = pb.start();

        ResultadoEspera resultado = runner.esperarConDrain(
                proc, 10, TimeUnit.SECONDS, "embedding", null, null);

        assertThat(resultado.finished()).isTrue();
        assertThat(resultado.exitCode()).isEqualTo(3);
    }
}
