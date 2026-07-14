package ar.scraper.ml;

import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PythonRunner#extraerEmbeddingsScript}, the package-private
 * test seam added for the judgment-day fix that publishes {@code ml_embeddings.py}
 * atomically and serializes concurrent extractions with a private static lock.
 *
 * <p>Covers A-002/B-002: two independently-schedulable call sites ({@code ejecutar}'s
 * synchronous path and {@code backfillEmbeddingsEnBackground}'s virtual thread) write
 * the same destination file; a reader must never observe a torn/partial copy.</p>
 */
@Epic("ML Pipeline")
@Feature("Python Runner")
@Story("Embeddings script extraction")
@DisplayName("PythonRunner — ml_embeddings.py extraction is atomic and race-free")
class PythonRunnerEmbeddingsExtractionTest {

    private final PythonRunner runner = new PythonRunner();

    @Test
    @DisplayName("extraerEmbeddingsScript writes a complete, non-empty file matching the classpath resource")
    void producesCompleteFile(@TempDir Path workDir) throws Exception {
        Path dest = runner.extraerEmbeddingsScript(workDir);

        assertThat(dest).exists();
        long expectedSize;
        try (var is = getClass().getResourceAsStream("/ml/ml_embeddings.py")) {
            assertThat(is).isNotNull();
            expectedSize = is.readAllBytes().length;
        }
        assertThat(Files.size(dest)).isEqualTo(expectedSize);
        assertThat(Files.size(dest)).isGreaterThan(0);
    }

    @Test
    @DisplayName("no leftover temp files remain after extraction")
    void leavesNoTempFileArtifacts(@TempDir Path workDir) throws Exception {
        runner.extraerEmbeddingsScript(workDir);

        try (var stream = Files.list(workDir)) {
            List<Path> leftovers = stream
                    .filter(p -> p.getFileName().toString().endsWith(".tmp"))
                    .toList();
            assertThat(leftovers).isEmpty();
        }
    }

    @Test
    @DisplayName("concurrent extractions never race and always leave a complete file")
    void toleratesConcurrentInvocation(@TempDir Path workDir) throws Exception {
        int threads = 8;
        long expectedSize;
        try (var is = getClass().getResourceAsStream("/ml/ml_embeddings.py")) {
            expectedSize = is.readAllBytes().length;
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Exception> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    runner.extraerEmbeddingsScript(workDir);
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).isEmpty();
        Path dest = workDir.resolve("ml_embeddings.py");
        assertThat(dest).exists();
        assertThat(Files.size(dest)).isEqualTo(expectedSize);
    }
}
