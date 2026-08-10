package ar.scraper.ml;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts the bundled ML Python scripts out of the jar into the run's work
 * directory.
 *
 * <p>Extracted verbatim from {@link PythonRunner} (backlog A3). Resource lookup
 * uses absolute classpath paths ({@code /ml/...}), so resolving them from this
 * class rather than PythonRunner reaches the same resources.</p>
 */
final class MlScriptExtractor {

    private MlScriptExtractor() {}

    /** Serializes concurrent extractions of ml_embeddings.py (see {@link #extraerEmbeddings}). */
    private static final Object EMBEDDINGS_EXTRACT_LOCK = new Object();

    static Path extraerPipeline(Path workDir) throws Exception {
        Path dest = workDir.resolve("ml_pipeline.py");
        try (InputStream is = MlScriptExtractor.class.getResourceAsStream("/ml/ml_pipeline.py")) {
            if (is == null) throw new FileNotFoundException("/ml/ml_pipeline.py no en classpath");
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    /** Extrae ml_train.py al directorio de trabajo */
    static Path extraerTrain(Path workDir) throws Exception {
        Path dest = workDir.resolve("ml_train.py");
        try (InputStream is = MlScriptExtractor.class.getResourceAsStream("/ml/ml_train.py")) {
            if (is != null) {
                Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            } else if (!Files.exists(dest)) {
                throw new java.io.FileNotFoundException("ml_train.py no encontrado en classpath ni en " + dest);
            }
        }
        return dest;
    }

    /**
     * Extrae ml_embeddings.py al directorio de trabajo, junto a ml_pipeline.py.
     * Requerido tanto por el stage-1b de refinamiento de imagen del pipeline
     * de scoring ({@code import ml_embeddings}) como por el launcher de
     * backfill — sin este archivo ambos degradan silenciosamente a solo-texto.
     * Mirror de {@link #extraerTrain}: tolera que el archivo ya exista.
     *
     * <p>Called from two independently-schedulable sites, both writing the same
     * {@code dest}. Publishes the destination ATOMICALLY (write to a unique temp
     * file, then {@code ATOMIC_MOVE} it into place, falling back to
     * {@code REPLACE_EXISTING} if the filesystem doesn't support atomic moves)
     * and serializes the write+move with a private static lock, so a concurrent
     * reader (Python subprocess importing the module) never observes a
     * torn/partial file.</p>
     */
    static Path extraerEmbeddings(Path workDir) throws Exception {
        Path dest = workDir.resolve("ml_embeddings.py");
        synchronized (EMBEDDINGS_EXTRACT_LOCK) {
            try (InputStream is = MlScriptExtractor.class.getResourceAsStream("/ml/ml_embeddings.py")) {
                if (is != null) {
                    Path tmp = Files.createTempFile(workDir, "ml_embeddings", ".py.tmp");
                    try {
                        Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
                        try {
                            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } catch (AtomicMoveNotSupportedException amnse) {
                            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                } else if (!Files.exists(dest)) {
                    throw new java.io.FileNotFoundException("ml_embeddings.py no encontrado en classpath ni en " + dest);
                }
            }
        }
        return dest;
    }
}
