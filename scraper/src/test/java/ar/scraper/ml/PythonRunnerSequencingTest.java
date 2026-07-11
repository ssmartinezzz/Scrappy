package ar.scraper.ml;

import ar.scraper.ml.PythonRunner.TrainingStatus;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
