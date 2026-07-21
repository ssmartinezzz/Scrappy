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
 *
 * <p>Batch 2 (decouple-services-postgres, design D5): {@code dbPath} is no
 * longer forwarded as a subprocess argv token, and {@code HF_HOME} is no
 * longer derived from it — every test below was updated to match. New
 * coverage: the {@code DATABASE_URL}/{@code SCRAPER_MODELS_ROOT}/
 * {@code HF_HOME} env trio {@link PythonRunner#aplicarEnvBaseDatosYModelos}
 * now applies to this subprocess.</p>
 */
@Epic("ML Pipeline")
@Feature("Python Runner")
@Story("Embeddings backfill launcher")
@DisplayName("PythonRunner — backfill ProcessBuilder wiring")
class PythonRunnerBackfillTest {

    private final PythonRunner runner = new PythonRunner();

    @Test
    void commandContainsBackfillSubcommandWithoutDbPathArgv() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", false, true);

        assertThat(pb.command()).contains("backfill");
        // design D5 + Batch 3 task 3.6: dbPath is no longer a parameter at
        // all — the subprocess reads DATABASE_URL from its env instead.
        assertThat(pb.command()).doesNotContain("scraper.db");
    }

    @Test
    void commandIncludesForceFlagWhenForceTrue() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", true, true);

        assertThat(pb.command()).contains("--force");
    }

    @Test
    void commandOmitsForceFlagWhenForceFalse() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", false, true);

        assertThat(pb.command()).doesNotContain("--force");
    }

    @Test
    void commandIncludesNoGpuFlagWhenGpuDisabled() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", false, false);

        assertThat(pb.command()).contains("--no-gpu");
    }

    @Test
    void commandOmitsNoGpuFlagWhenGpuEnabled() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", false, true);

        assertThat(pb.command()).doesNotContain("--no-gpu");
    }

    @Test
    void setsCudaVisibleDevicesWhenGpuDisabled() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", false, false);

        assertThat(pb.environment()).containsEntry("CUDA_VISIBLE_DEVICES", "-1");
    }

    @Test
    void leavesCudaVisibleDevicesUnsetWhenGpuEnabled() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", false, true);

        assertThat(pb.environment()).doesNotContainKey("CUDA_VISIBLE_DEVICES");
    }

    @Test
    void setsUtf8EnvironmentForSubprocess() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", false, true);

        assertThat(pb.environment())
                .containsEntry("PYTHONIOENCODING", "utf-8")
                .containsEntry("PYTHONUTF8", "1")
                .containsEntry("PYTHONUNBUFFERED", "1");
    }

    // ── Batch 2 (decouple-services-postgres, design D5): SCRAPER_MODELS_ROOT
    // ── / HF_HOME derived from it, no longer from dbPath ─────────────────────
    // Without HF_HOME the backfill subprocess can't find the installer-warmed
    // Marqo weights cache and would re-download ~300MB (or fail offline).
    // Must match the installer's pinning (INSTALAR_Y_CORRER.bat step 3g:
    // HF_HOME=%ROOT%\_models\marqo) and ml_embeddings.py's own fallback
    // (`_default_hf_home()`: `<SCRAPER_MODELS_ROOT or _models>/marqo`).

    @Test
    void setsHfHomeToModelsMarqoDirectoryUnderWorkDirFallback() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", false, true);

        // No SCRAPER_MODELS_ROOT env var set in this test JVM — falls back
        // to workDir/_models, matching resolveModelsRoot's own contract.
        Path expected = Paths.get("").toAbsolutePath().resolve("_models").resolve("marqo");
        assertThat(pb.environment()).containsEntry("HF_HOME", expected.toString());
    }

    @Test
    void setsScraperModelsRootEnvVar() {
        ProcessBuilder pb = runner.construirProcessBuilderBackfill(
                "python", "script.py", false, true);

        Path expected = Paths.get("").toAbsolutePath().resolve("_models");
        assertThat(pb.environment()).containsEntry("SCRAPER_MODELS_ROOT", expected.toString());
    }

    // ── resolveModelsRoot / hfHomeParaModelsRoot: pure test seams ───────────

    @Test
    void resolveModelsRootPrefersExplicitEnvValue(@org.junit.jupiter.api.io.TempDir Path workDir) {
        String resolved = PythonRunner.resolveModelsRoot("/custom/models/root", workDir);
        assertThat(resolved).isEqualTo("/custom/models/root");
    }

    @Test
    void resolveModelsRootFallsBackToWorkDirModelsWhenEnvUnset(
            @org.junit.jupiter.api.io.TempDir Path workDir) {
        String resolved = PythonRunner.resolveModelsRoot(null, workDir);
        assertThat(resolved).isEqualTo(workDir.resolve("_models").toString());
    }

    @Test
    void resolveModelsRootFallsBackToWorkDirModelsWhenEnvBlank(
            @org.junit.jupiter.api.io.TempDir Path workDir) {
        String resolved = PythonRunner.resolveModelsRoot("   ", workDir);
        assertThat(resolved).isEqualTo(workDir.resolve("_models").toString());
    }

    @Test
    void hfHomeParaModelsRootAppendsMarqo() {
        String resolved = PythonRunner.hfHomeParaModelsRoot("/some/root");
        assertThat(resolved).isEqualTo(Paths.get("/some/root").resolve("marqo").toString());
    }

    // ── toPsycopgDsn: pure test seam (decouple-services-postgres, Batch 4) ──
    //
    // Spring's spring.datasource.url MUST keep the `jdbc:postgresql://...`
    // scheme (required by the JDBC PG driver / DriverManager), but psycopg2
    // does NOT understand a `jdbc:` prefix at all (libpq only recognizes the
    // `postgresql://`/`postgres://` schemes) — forwarding DATABASE_URL
    // verbatim to the Python subprocess (as aplicarEnvBaseDatosYModelos did
    // through Batch 2/3) silently breaks every psycopg2.connect(dsn) call
    // the moment DATABASE_URL is actually a real jdbc: URL (as the Batch 4
    // installer's generated .env sets it) rather than left unset in a test.

    @Test
    void toPsycopgDsnStripsJdbcPrefix() {
        String dsn = PythonRunner.toPsycopgDsn(
                "jdbc:postgresql://127.0.0.1:5432/scraper", null, null);
        assertThat(dsn).isEqualTo("postgresql://127.0.0.1:5432/scraper");
    }

    @Test
    void toPsycopgDsnAppendsUsernameAndPasswordAsQueryParams() {
        String dsn = PythonRunner.toPsycopgDsn(
                "jdbc:postgresql://127.0.0.1:5432/scraper", "postgres", "secret");
        assertThat(dsn).isEqualTo("postgresql://127.0.0.1:5432/scraper?user=postgres&password=secret");
    }

    @Test
    void toPsycopgDsnAppendsOnlyUsernameWhenPasswordBlank() {
        String dsn = PythonRunner.toPsycopgDsn(
                "jdbc:postgresql://127.0.0.1:5432/scraper", "postgres", "");
        assertThat(dsn).isEqualTo("postgresql://127.0.0.1:5432/scraper?user=postgres");
    }

    @Test
    void toPsycopgDsnLeavesNonJdbcUrlUnchangedWhenAlreadyPlain() {
        String dsn = PythonRunner.toPsycopgDsn(
                "postgresql://127.0.0.1:5432/scraper", null, null);
        assertThat(dsn).isEqualTo("postgresql://127.0.0.1:5432/scraper");
    }

    @Test
    void toPsycopgDsnReturnsNullWhenInputNull() {
        assertThat(PythonRunner.toPsycopgDsn(null, "postgres", "x")).isNull();
    }

    @Test
    void aplicarEnvBaseDatosYModelosSetsPsycopgFormattedDatabaseUrlFromEnv(
            @org.junit.jupiter.api.io.TempDir Path workDir) throws Exception {
        // aplicarEnvBaseDatosYModelos reads System.getenv() directly (not
        // injectable), so this exercises it end-to-end only when the test
        // JVM's own env happens to carry these vars (CI/dev with a real
        // Postgres wired) — otherwise it degrades to a no-DATABASE_URL no-op,
        // which is asserted instead. The pure toPsycopgDsn tests above are
        // the primary, environment-independent coverage for the translation.
        ProcessBuilder pb = new ProcessBuilder("python", "script.py");
        PythonRunner runner = new PythonRunner();
        runner.aplicarEnvBaseDatosYModelos(pb, workDir);

        String jvmDatabaseUrl = System.getenv("DATABASE_URL");
        if (jvmDatabaseUrl == null) {
            assertThat(pb.environment()).doesNotContainKey("DATABASE_URL");
        } else {
            assertThat(pb.environment().get("DATABASE_URL")).doesNotStartWith("jdbc:");
        }
    }
}
