package ar.scraper.ml;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code useGpu} force-CPU wiring added to {@link PythonRunner}
 * (scraper-cronjobs PR1, per-job GPU flag — see ADR-2, sdd/scraper-cronjobs/design).
 *
 * <p>{@link PythonRunner#ejecutar} and {@link PythonRunner#entrenarEnBackground}
 * spawn real Python subprocesses and can't be exercised end-to-end without a
 * live interpreter. To keep this testable, the {@code ProcessBuilder}
 * construction was extracted into package-private seams
 * ({@code construirProcessBuilderScoring}, {@code construirProcessBuilderEntrenamiento},
 * {@code construirProcessBuilderProbe}) that build (but never {@code .start()})
 * the process — these tests assert directly on the resulting environment map.</p>
 */
@Epic("ML Pipeline")
@Feature("Python Runner")
@Story("GPU flag")
@DisplayName("PythonRunner — force-CPU wiring via CUDA_VISIBLE_DEVICES")
class PythonRunnerGpuFlagTest {

    private final PythonRunner runner = new PythonRunner();

    @Test
    void scoringProcessBuilderSetsCudaVisibleDevicesWhenGpuDisabled() {
        ProcessBuilder pb = runner.construirProcessBuilderScoring(
                "python", Path.of("script.py"), Path.of("prod.json"),
                Path.of("out.json"), Path.of("hist.json"), Path.of("."), false);

        assertThat(pb.environment()).containsEntry("CUDA_VISIBLE_DEVICES", "-1");
    }

    @Test
    void scoringProcessBuilderLeavesCudaVisibleDevicesUnsetWhenGpuEnabled() {
        ProcessBuilder pb = runner.construirProcessBuilderScoring(
                "python", Path.of("script.py"), Path.of("prod.json"),
                Path.of("out.json"), Path.of("hist.json"), Path.of("."), true);

        assertThat(pb.environment()).doesNotContainKey("CUDA_VISIBLE_DEVICES");
    }

    @Test
    void trainingProcessBuilderSetsCudaVisibleDevicesWhenGpuDisabled() {
        ProcessBuilder pb = runner.construirProcessBuilderEntrenamiento(
                List.of("python", "ml_train.py", "db.sqlite"), Path.of("."), false);

        assertThat(pb.environment()).containsEntry("CUDA_VISIBLE_DEVICES", "-1");
    }

    @Test
    void trainingProcessBuilderLeavesCudaVisibleDevicesUnsetWhenGpuEnabled() {
        ProcessBuilder pb = runner.construirProcessBuilderEntrenamiento(
                List.of("python", "ml_train.py", "db.sqlite"), Path.of("."), true);

        assertThat(pb.environment()).doesNotContainKey("CUDA_VISIBLE_DEVICES");
    }

    @Test
    void probeProcessBuilderSetsCudaVisibleDevicesWhenForceCpu() {
        ProcessBuilder pb = runner.construirProcessBuilderProbe("python", "import torch", true);

        assertThat(pb.environment()).containsEntry("CUDA_VISIBLE_DEVICES", "-1");
    }

    @Test
    void probeProcessBuilderLeavesCudaVisibleDevicesUnsetWhenNotForceCpu() {
        ProcessBuilder pb = runner.construirProcessBuilderProbe("python", "import torch", false);

        assertThat(pb.environment()).doesNotContainKey("CUDA_VISIBLE_DEVICES");
    }

    @Test
    void setUseGpuDefaultsToTrue() {
        assertThat(runner.isUseGpu()).isTrue();
    }

    @Test
    void setUseGpuUpdatesFlag() {
        runner.setUseGpu(false);
        assertThat(runner.isUseGpu()).isFalse();

        runner.setUseGpu(true);
        assertThat(runner.isUseGpu()).isTrue();
    }

    // ── Regression: entrenarEnBackground's tieneCuda/tienePytorch call site ──
    // previously passed useGpuSnapshot (true = GPU allowed) directly as the
    // probes' forceCpu argument (true = force CPU on the probe subprocess) —
    // the INVERSE polarity — which forced CPU on the CUDA probe whenever GPU
    // was enabled, defeating detection. forceCpuParaProbes() is the extracted,
    // named decision the call site now uses, so the polarity is asserted here
    // without spawning a real Python interpreter.
    @Test
    void forceCpuParaProbesIsFalseWhenGpuEnabled() {
        assertThat(runner.forceCpuParaProbes(true)).isFalse();
    }

    @Test
    void forceCpuParaProbesIsTrueWhenGpuDisabled() {
        assertThat(runner.forceCpuParaProbes(false)).isTrue();
    }
}
