package ar.scraper.ml;

import ar.scraper.ml.PythonRunner.TrainingStatus;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the pure decision seams backing
 * {@link PythonRunner#construirIndiceVisualEnBackground}, the T5.4
 * sequencing entrypoint that runs text re-training FIRST, then the
 * embeddings backfill, on ONE background thread — both phases reporting
 * into the SAME {@link TrainingStatus} object via a distinct
 * {@code phase} ("training" vs "embedding").
 *
 * <p>Mirrors the rest of this test class family: the actual subprocess
 * spawn/wait/timeout machinery isn't exercised here (no live Python
 * interpreter dependency) — only the pure decision logic the sequencing
 * entrypoint depends on: whether to skip a stale-but-recent text model,
 * and how a subprocess's stdout progress line maps onto the shared
 * {@link TrainingStatus}, tagged with the caller-supplied macro phase.</p>
 */
@Epic("ML Pipeline")
@Feature("Python Runner")
@Story("Sequenced text-training + embeddings backfill (T5.4)")
@DisplayName("PythonRunner — construirIndiceVisualEnBackground sequencing seams")
class PythonRunnerSequencingTest {

    private final PythonRunner runner = new PythonRunner();

    // ── debeSaltearEntrenamientoPorFrescura ──────────────────────────────────

    @Test
    void skipsWhenModelExistsAndRecentAndNotForced() {
        long unaHora = 3600_000L;
        assertThat(runner.debeSaltearEntrenamientoPorFrescura(true, false, unaHora)).isTrue();
    }

    @Test
    void doesNotSkipWhenForceRetrainTrueEvenIfRecent() {
        long unaHora = 3600_000L;
        assertThat(runner.debeSaltearEntrenamientoPorFrescura(true, true, unaHora)).isFalse();
    }

    @Test
    void doesNotSkipWhenModelDoesNotExist() {
        long unaHora = 3600_000L;
        assertThat(runner.debeSaltearEntrenamientoPorFrescura(false, false, unaHora)).isFalse();
    }

    @Test
    void doesNotSkipWhenModelOlderThan24h() {
        long veinticincoHoras = 25L * 3600_000L;
        assertThat(runner.debeSaltearEntrenamientoPorFrescura(true, false, veinticincoHoras)).isFalse();
    }

    @Test
    void borderlineExactly24hIsNotSkipped() {
        long veinticuatroHoras = 24L * 3600_000L;
        assertThat(runner.debeSaltearEntrenamientoPorFrescura(true, false, veinticuatroHoras)).isFalse();
    }

    // ── parsearLineaProgreso ──────────────────────────────────────────────────

    @Test
    void parsesValidProgressLineTaggedWithMacroPhaseTraining() {
        TrainingStatus previo = new TrainingStatus(true, "training", 0, "", "2026-01-01T00:00:00Z");
        TrainingStatus result = runner.parsearLineaProgreso(
                "{\"phase\":\"text\",\"pct\":42,\"msg\":\"entrenando\"}", "training", previo);

        assertThat(result.phase()).isEqualTo("training");
        assertThat(result.pct()).isEqualTo(42);
        assertThat(result.msg()).isEqualTo("entrenando");
        assertThat(result.startedAt()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(result.running()).isTrue();
    }

    @Test
    void parsesValidProgressLineTaggedWithMacroPhaseEmbedding() {
        TrainingStatus previo = new TrainingStatus(true, "embedding", 0, "", "2026-01-01T00:00:00Z");
        TrainingStatus result = runner.parsearLineaProgreso(
                "{\"phase\":\"embedding\",\"pct\":70,\"msg\":\"clasificando\"}", "embedding", previo);

        assertThat(result.phase()).isEqualTo("embedding");
        assertThat(result.pct()).isEqualTo(70);
        assertThat(result.msg()).isEqualTo("clasificando");
    }

    @Test
    void nonJsonLineLeavesStatusUnchanged() {
        TrainingStatus previo = new TrainingStatus(true, "training", 10, "prev", "t0");
        TrainingStatus result = runner.parsearLineaProgreso("not json at all", "training", previo);

        assertThat(result).isEqualTo(previo);
    }

    @Test
    void jsonLineWithoutPctKeyLeavesStatusUnchanged() {
        TrainingStatus previo = new TrainingStatus(true, "training", 10, "prev", "t0");
        TrainingStatus result = runner.parsearLineaProgreso("{\"status\":\"ok\"}", "training", previo);

        assertThat(result).isEqualTo(previo);
    }

    @Test
    void malformedJsonLeavesStatusUnchanged() {
        TrainingStatus previo = new TrainingStatus(true, "training", 10, "prev", "t0");
        TrainingStatus result = runner.parsearLineaProgreso("{\"pct\": not-a-number", "training", previo);

        assertThat(result).isEqualTo(previo);
    }

    @Test
    void nullLineLeavesStatusUnchanged() {
        TrainingStatus previo = new TrainingStatus(true, "training", 10, "prev", "t0");
        TrainingStatus result = runner.parsearLineaProgreso(null, "training", previo);

        assertThat(result).isEqualTo(previo);
    }

    // ── extraerProcesadasDeLineaProgreso (RESI-002) ──────────────────────────

    @Test
    void extraerProcesadasParsesProcessedCountFromPerRowProgressLine() {
        String line = "{\"phase\":\"embedding\",\"pct\":25,\"msg\":\"5/20 — https://site.com/p\"}";
        assertThat(runner.extraerProcesadasDeLineaProgreso(line)).isEqualTo(5);
    }

    @Test
    void extraerProcesadasReturnsMinusOneForNoPendingProductsMessage() {
        String line = "{\"phase\":\"embedding\",\"pct\":100,\"msg\":\"sin productos pendientes\"}";
        assertThat(runner.extraerProcesadasDeLineaProgreso(line)).isEqualTo(-1);
    }

    @Test
    void extraerProcesadasReturnsMinusOneForCompletionMessage() {
        String line = "{\"phase\":\"embedding\",\"pct\":100,\"msg\":\"backfill completo\"}";
        assertThat(runner.extraerProcesadasDeLineaProgreso(line)).isEqualTo(-1);
    }

    @Test
    void extraerProcesadasReturnsMinusOneForNonJsonOrMalformedLine() {
        assertThat(runner.extraerProcesadasDeLineaProgreso("not json")).isEqualTo(-1);
        assertThat(runner.extraerProcesadasDeLineaProgreso(null)).isEqualTo(-1);
        assertThat(runner.extraerProcesadasDeLineaProgreso("{\"pct\": not-a-number")).isEqualTo(-1);
    }

    // ── esBackfillDegradado (RESI-002) ───────────────────────────────────────

    @Test
    void backfillIsDegradedWhenEveryProcessedRowHadNoVisualSignal() {
        assertThat(runner.esBackfillDegradado(20, 20)).isTrue();
    }

    @Test
    void backfillIsNotDegradedWhenSomeRowsPersistedSignal() {
        assertThat(runner.esBackfillDegradado(20, 5)).isFalse();
    }

    @Test
    void backfillIsNotDegradedWhenNoRowsWereProcessed() {
        // filasProcesadas == -1 (never observed a per-row progress line, e.g.
        // "sin productos pendientes" run) must never be reported as degraded.
        assertThat(runner.esBackfillDegradado(-1, 0)).isFalse();
        assertThat(runner.esBackfillDegradado(0, 0)).isFalse();
    }

    // ── debeResetearAIdleTrasSecuencia (RESI-003) ────────────────────────────

    @Test
    void resetsToIdleOnlyWhenBothPhasesSucceed() {
        assertThat(runner.debeResetearAIdleTrasSecuencia(true, true)).isTrue();
    }

    @Test
    void doesNotResetToIdleWhenTrainingPhaseFails() {
        assertThat(runner.debeResetearAIdleTrasSecuencia(false, true)).isFalse();
    }

    @Test
    void doesNotResetToIdleWhenBackfillPhaseFails() {
        assertThat(runner.debeResetearAIdleTrasSecuencia(true, false)).isFalse();
    }

    @Test
    void doesNotResetToIdleWhenBothPhasesFail() {
        assertThat(runner.debeResetearAIdleTrasSecuencia(false, false)).isFalse();
    }

    // ── Phase methods write a durable error terminal status on failure (RESI-003) ──
    // Uses a nonexistent "python" executable so ProcessBuilder.start() throws
    // IOException immediately — deterministic and fast, no real interpreter
    // needed, and exercises the REAL call sites (not just the pure seams above).

    private static final String PYTHON_INEXISTENTE = "this-python-executable-does-not-exist-xyz";

    @Test
    void trainingPhaseWritesDurableErrorStatusWhenProcessCannotStart(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path workDir) {
        boolean ok = runner.ejecutarFaseEntrenamientoSecuenciada(
                PYTHON_INEXISTENTE, workDir, workDir.resolve("scraper.db").toString(),
                false, false, 8, false);

        assertThat(ok).isFalse();
        TrainingStatus estado = runner.getTrainingStatus();
        assertThat(estado.running()).isFalse();
        assertThat(estado.phase()).isEqualTo("error");
        assertThat(estado.msg()).contains("training");
    }

    @Test
    void backfillPhaseWritesDurableErrorStatusWhenProcessCannotStart(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path workDir) {
        boolean ok = runner.ejecutarFaseBackfillSecuenciada(
                PYTHON_INEXISTENTE, workDir, workDir.resolve("scraper.db").toString(), false, false);

        assertThat(ok).isFalse();
        TrainingStatus estado = runner.getTrainingStatus();
        assertThat(estado.running()).isFalse();
        assertThat(estado.phase()).isEqualTo("error");
        assertThat(estado.msg()).contains("embedding");
    }

    // ── FIXV-001: construirIndiceVisualEnBackground terminal-status contract ──
    // (4R correction round, fix-delta escalation) — end-to-end coverage of the
    // ENTRYPOINT itself via the new package-private synchronous seam
    // ejecutarSecuenciaIndiceVisual (same logic the public async entrypoint
    // delegates to, minus the virtual-thread wrapper + detectarPython() call,
    // so a test can invoke it directly and assert on the final status without
    // polling a background thread). A single controllable "python" stand-in —
    // a tiny .bat that inspects argv[0] (the script path Java passes) and
    // exits 0/1 depending on whether it's being invoked as ml_train.py
    // (training) or ml_embeddings.py (backfill) — lets each of the 4
    // training-x-backfill outcome combinations be forced deterministically,
    // without a real Python interpreter or model weights.

    /**
     * Writes a batch-file "python" stand-in to {@code dir} that exits
     * {@code trainExitCode} when invoked with an {@code ml_train.py} script
     * path as its first argument (the training phase) and
     * {@code backfillExitCode} when invoked with an {@code ml_embeddings.py}
     * script path (the backfill phase) — matching exactly how
     * {@link PythonRunner#ejecutarFaseEntrenamientoSecuenciada}/
     * {@link PythonRunner#ejecutarFaseBackfillSecuenciada} build their
     * subprocess command lines. Any other invocation (e.g. the
     * {@code tieneCuda}/{@code tienePytorch} probes, argv[0] {@code -c})
     * exits 0 with no output. Produces zero stdout in every case, so
     * {@code esperarConDrain}'s read loops hit EOF immediately — fast,
     * deterministic, no hang risk. The stand-in is a batch file, so the four
     * tests that execute it are Windows-only ({@code @EnabledOnOs(WINDOWS)});
     * on Linux the .bat cannot exec, which silently forces the both-phases-fail
     * outcome and makes half the matrix pass for the wrong reason.
     */
    private String escribirPythonFalso(java.nio.file.Path dir, int trainExitCode, int backfillExitCode)
            throws Exception {
        java.nio.file.Path script = dir.resolve("fake_python.bat");
        String contenido = "@echo off\r\n"
                + "echo %1 | findstr /C:\"ml_train.py\" >nul\r\n"
                + "if %errorlevel%==0 exit /b " + trainExitCode + "\r\n"
                + "echo %1 | findstr /C:\"ml_embeddings.py\" >nul\r\n"
                + "if %errorlevel%==0 exit /b " + backfillExitCode + "\r\n"
                + "exit /b 0\r\n";
        java.nio.file.Files.writeString(script, contenido);
        return script.toAbsolutePath().toString();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("both phases succeed -> terminal status resets to idle")
    void bothPhasesSucceedEndsIdle(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
        String python = escribirPythonFalso(workDir, 0, 0);

        runner.ejecutarSecuenciaIndiceVisual(python, workDir, workDir.resolve("scraper.db").toString(),
                false, false, 8, false, false);

        TrainingStatus estado = runner.getTrainingStatus();
        assertThat(estado.running()).isFalse();
        assertThat(estado.phase()).isEqualTo("idle");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("FIXV-001 regression: training fails, backfill succeeds -> durable non-running error status, never stuck running")
    void trainingFailsBackfillSucceedsEndsInDurableErrorNeverStuckRunning(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
        String python = escribirPythonFalso(workDir, 1, 0);

        runner.ejecutarSecuenciaIndiceVisual(python, workDir, workDir.resolve("scraper.db").toString(),
                false, false, 8, false, false);

        TrainingStatus estado = runner.getTrainingStatus();
        // The core FIXV-001 bug: this status must NEVER be left running=true
        // (permanently "in progress" for a poller) and must NEVER be plain
        // idle (which would silently erase the training failure) — it must be
        // a durable, non-running error/degraded terminal state.
        assertThat(estado.running()).isFalse();
        assertThat(estado.phase()).isNotEqualTo("idle");
        assertThat(estado.phase()).isEqualTo("error");
        assertThat(estado.msg()).contains("training");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("training succeeds, backfill fails -> durable error status (pre-existing, unaffected by the fix)")
    void trainingSucceedsBackfillFailsEndsInDurableError(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) throws Exception {
        String python = escribirPythonFalso(workDir, 0, 1);

        runner.ejecutarSecuenciaIndiceVisual(python, workDir, workDir.resolve("scraper.db").toString(),
                false, false, 8, false, false);

        TrainingStatus estado = runner.getTrainingStatus();
        assertThat(estado.running()).isFalse();
        assertThat(estado.phase()).isNotEqualTo("idle");
        assertThat(estado.phase()).isEqualTo("error");
        assertThat(estado.msg()).contains("embedding");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("both phases fail -> durable error status (pre-existing, unaffected by the fix)")
    void bothPhasesFailEndInDurableError(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir)
            throws Exception {
        String python = escribirPythonFalso(workDir, 1, 1);

        runner.ejecutarSecuenciaIndiceVisual(python, workDir, workDir.resolve("scraper.db").toString(),
                false, false, 8, false, false);

        TrainingStatus estado = runner.getTrainingStatus();
        assertThat(estado.running()).isFalse();
        assertThat(estado.phase()).isNotEqualTo("idle");
        assertThat(estado.phase()).isEqualTo("error");
    }

    // ── T6.2b: re-entrancy guard for construirIndiceVisualEnBackground ──────
    // (deferred WARNING from PR5's 4R review, obs #369 — "reentrancy guard for
    // construirIndiceVisualEnBackground, do in PR6 when wiring /api/ml/entrenar,
    // mirror ApiController isTrainingRunning() guard"). ApiController already
    // rejects a second HTTP request when isTrainingRunning() is true, but that
    // check happens on the HTTP thread BEFORE the virtual thread that actually
    // flips trainingStatus to running=true ever starts running — a burst of
    // near-simultaneous calls could both observe running=false and both launch
    // a sequence. intentarReservarSecuenciaIndiceVisual() closes that window by
    // making the reservation atomic (CAS) and synchronous, at the top of the
    // public entrypoint, before any thread is spawned.

    @Test
    @DisplayName("guard reserves the slot from idle and flips trainingStatus synchronously")
    void reservaSecuenciaTieneExitoDesdeIdle() {
        assertThat(runner.isTrainingRunning()).isFalse();

        boolean reservado = runner.intentarReservarSecuenciaIndiceVisual();

        assertThat(reservado).isTrue();
        assertThat(runner.isTrainingRunning()).isTrue();
    }

    @Test
    @DisplayName("guard rejects a second reservation while one is already in flight")
    void reservaSecuenciaFallaCuandoYaHayUnaEnCurso() {
        assertThat(runner.intentarReservarSecuenciaIndiceVisual()).isTrue();

        boolean segundaReserva = runner.intentarReservarSecuenciaIndiceVisual();

        assertThat(segundaReserva).isFalse();
    }

    @Test
    @DisplayName("guard succeeds again from a durable non-idle error state (running=false but phase!=idle)")
    void reservaSecuenciaTieneExitoTrasEstadoDeErrorDurable(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path workDir) {
        runner.ejecutarFaseEntrenamientoSecuenciada(PYTHON_INEXISTENTE, workDir,
                workDir.resolve("scraper.db").toString(), false, false, 8, false);
        assertThat(runner.getTrainingStatus().phase()).isEqualTo("error");
        assertThat(runner.isTrainingRunning()).isFalse();

        assertThat(runner.intentarReservarSecuenciaIndiceVisual()).isTrue();
    }

    @Test
    @DisplayName("construirIndiceVisualEnBackground no-ops when a sequence is already reserved/running")
    void construirIndiceVisualNoOpCuandoYaHayUnaSecuenciaEnCurso() throws Exception {
        assertThat(runner.intentarReservarSecuenciaIndiceVisual()).isTrue();
        TrainingStatus antes = runner.getTrainingStatus();

        boolean iniciado = runner.construirIndiceVisualEnBackground("scraper.db", false, false, 8, false);
        Thread.sleep(100);

        assertThat(iniciado).isFalse();
        assertThat(runner.getTrainingStatus()).isEqualTo(antes);
    }

    // ── RESI-002 ≡ RELY-001 (4R PR6 follow-up): the CAS result must be ──────
    // observable by the caller. Before this fix the guard was void inside the
    // entrypoint, so ApiController could not distinguish "sequence launched"
    // from "silently dropped" — a near-simultaneous double POST /api/ml/entrenar
    // gave the loser a 200 "started" for a request that never ran.

    @Test
    @DisplayName("RESI-002: entrypoint returns the CAS result — winner true, immediate second caller false")
    void construirIndiceVisualDevuelveResultadoDelCas() {
        // Seams overridden so the winner never spawns a real sequence thread
        // (and never scans the real environment for a Python interpreter).
        PythonRunner sinHiloReal = new PythonRunner() {
            @Override String detectarPython() { return "python-fake"; }
            @Override Thread lanzarHiloSecuencia(Runnable body) { return new Thread(body); } // nunca started
        };

        boolean ganador  = sinHiloReal.construirIndiceVisualEnBackground("scraper.db", false, false, 8, false);
        boolean perdedor = sinHiloReal.construirIndiceVisualEnBackground("scraper.db", false, false, 8, false);

        assertThat(ganador).isTrue();
        assertThat(perdedor).isFalse();
        assertThat(sinHiloReal.isTrainingRunning()).isTrue(); // winner's reservation still held
    }

    // ── RESI-001 (4R PR6 follow-up, deterministic): the CAS reservation is ──
    // taken BEFORE Thread.ofVirtual().start(). If starting the thread throws
    // (e.g. resource exhaustion), the reservation must be released — otherwise
    // running=true is stuck forever and POST /api/ml/entrenar answers 400/409
    // until the JVM restarts.

    @Test
    @DisplayName("RESI-001: thread-start failure writes a durable error, releases the slot, and rethrows")
    void falloAlLanzarHiloLiberaLaReservaYPropagaElError() {
        PythonRunner fallaAlLanzar = new PythonRunner() {
            @Override String detectarPython() { return "python-fake"; }
            @Override Thread lanzarHiloSecuencia(Runnable body) {
                throw new IllegalStateException("no se pudo crear el hilo");
            }
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                fallaAlLanzar.construirIndiceVisualEnBackground("scraper.db", false, false, 8, false))
            .isInstanceOf(IllegalStateException.class);

        TrainingStatus estado = fallaAlLanzar.getTrainingStatus();
        assertThat(estado.running()).isFalse();            // never stuck at running=true
        assertThat(estado.phase()).isEqualTo("error");     // durable, observable via /api/ml/estado
        // The slot must be recoverable: a retry can reserve again without a restart.
        assertThat(fallaAlLanzar.intentarReservarSecuenciaIndiceVisual()).isTrue();
    }
}
