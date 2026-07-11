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
}
