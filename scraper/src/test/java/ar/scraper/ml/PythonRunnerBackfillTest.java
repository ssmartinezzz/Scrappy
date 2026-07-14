package ar.scraper.ml;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@code construirProcessBuilderBackfill} test seam added
 * in PR5 for {@link PythonRunner#backfillEmbeddingsEnBackground}.
 *
 * <p>Mirrors {@link PythonRunnerGpuFlagTest}: {@code backfillEmbeddingsEnBackground}
 * spawns a real Python subprocess (never exercised end-to-end here). The
 * {@code ProcessBuilder} construction is extracted into a package-private seam
 * that builds (but never {@code .start()}s) the process, so these tests assert
 * directly on the resulting command and environment map.</p>
 */
@Epic("ML Pipeline")
@Feature("Python Runner")
@Story("Embeddings backfill launcher")
@DisplayName("PythonRunner — backfill ProcessBuilder wiring")
class PythonRunnerBackfillTest {

    private final PythonRunner runner = new PythonRunner();

    @Test
    void commandContainsBackfillSubcommandAndDbPath() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", "scraper.db", false, true);

        assertThat(pb.command()).contains("backfill", "scraper.db");
    }

    @Test
    void commandIncludesForceFlagWhenForceTrue() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", "scraper.db", true, true);

        assertThat(pb.command()).contains("--force");
    }

    @Test
    void commandOmitsForceFlagWhenForceFalse() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", "scraper.db", false, true);

        assertThat(pb.command()).doesNotContain("--force");
    }

    @Test
    void commandIncludesNoGpuFlagWhenGpuDisabled() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", "scraper.db", false, false);

        assertThat(pb.command()).contains("--no-gpu");
    }

    @Test
    void commandOmitsNoGpuFlagWhenGpuEnabled() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", "scraper.db", false, true);

        assertThat(pb.command()).doesNotContain("--no-gpu");
    }

    @Test
    void setsCudaVisibleDevicesWhenGpuDisabled() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", "scraper.db", false, false);

        assertThat(pb.environment()).containsEntry("CUDA_VISIBLE_DEVICES", "-1");
    }

    @Test
    void leavesCudaVisibleDevicesUnsetWhenGpuEnabled() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", "scraper.db", false, true);

        assertThat(pb.environment()).doesNotContainKey("CUDA_VISIBLE_DEVICES");
    }

    @Test
    void setsUtf8EnvironmentForSubprocess() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", "scraper.db", false, true);

        assertThat(pb.environment())
                .containsEntry("PYTHONIOENCODING", "utf-8")
                .containsEntry("PYTHONUTF8", "1")
                .containsEntry("PYTHONUNBUFFERED", "1");
    }

    // ── T5.3: HF_HOME pinning ────────────────────────────────────────────────
    // Without HF_HOME the backfill subprocess can't find the installer-warmed
    // Marqo weights cache and would re-download ~300MB (or fail offline).
    // Must match the installer's pinning (INSTALAR_Y_CORRER.bat step 3g:
    // HF_HOME=%ROOT%\_models\marqo`) and ml_embeddings.py's
    // `_default_hf_home(db_path)` (Path(db_path).resolve().parent / "_models" / "marqo").

    @Test
    void setsHfHomeToModelsMarqoDirectoryNextToDbPath() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", "scraper.db", false, true);

        Path expected = Paths.get("scraper.db").toAbsolutePath().getParent()
                .resolve("_models").resolve("marqo");
        assertThat(pb.environment()).containsEntry("HF_HOME", expected.toString());
    }

    @Test
    void setsHfHomeRelativeToAnAbsoluteDbPathsParentDirectory(
            @org.junit.jupiter.api.io.TempDir Path installRoot) {
        // TempDir yields a genuinely absolute path on every OS; a literal
        // "C:/..." is relative on Linux and gets resolved against the CWD.
        Path absoluteDb = installRoot.resolve("scraper.db");
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", absoluteDb.toString(), false, true);

        Path expected = absoluteDb.getParent().resolve("_models").resolve("marqo");
        assertThat(pb.environment()).containsEntry("HF_HOME", expected.toString());
    }
}
