package ar.scraper.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

@Component
public class PythonRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PythonRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TIMEOUT_SEC = 600;

    public record TrainingStatus(
            boolean running, String phase, int pct, String msg, String startedAt) {
        public static TrainingStatus idle() {
            return new TrainingStatus(false, "idle", 0, "", null);
        }
    }

    private final java.util.concurrent.atomic.AtomicReference<TrainingStatus> trainingStatus =
        new java.util.concurrent.atomic.AtomicReference<>(TrainingStatus.idle());

    /** Status of the visual-attribute backfill launched by {@link #backfillEmbeddingsEnBackground}. */
    public record BackfillStatus(boolean running, int pct, String msg, String startedAt) {
        public static BackfillStatus idle() {
            return new BackfillStatus(false, 0, "", null);
        }
    }

    private final java.util.concurrent.atomic.AtomicReference<BackfillStatus> backfillStatus =
        new java.util.concurrent.atomic.AtomicReference<>(BackfillStatus.idle());

    /**
     * Flag GPU/CPU por-job para los cron runs (scraper-cronjobs PR1, ver ADR-2
     * en sdd/scraper-cronjobs/design). {@code true} (default) = comportamiento
     * actual sin cambios (probe CUDA si está disponible). {@code false} = fuerza
     * CPU en TODOS los subprocesos Python (scoring, entrenamiento y los probes
     * {@code tieneCuda}/{@code tienePytorch}) seteando {@code CUDA_VISIBLE_DEVICES=-1},
     * sin tocar ml_pipeline.py/ml_train.py (ambos ya bifurcan por
     * {@code torch.cuda.is_available()}). {@code volatile} porque el scheduler/
     * runner setea el flag desde otro hilo antes de disparar el scrape; los
     * métodos públicos capturan el valor en una variable local ANTES de lanzar
     * su hilo virtual (snapshot-at-entry) para evitar una carrera con el reset
     * en el {@code finally} de {@code CronJobRunner}.
     */
    private volatile boolean useGpu = true;

    public void setUseGpu(boolean v) { this.useGpu = v; }
    public boolean isUseGpu() { return useGpu; }

    /**
     * Ejecuta el pipeline ML con contrato de 3 estados:
     * - NOT_RUN (Python no encontrado, timeout, exit!=0, sin output, excepción) → retorna {@code null}
     * - EMPTY/VALID (proceso OK, output parseado tal cual) → retorna el {@link JsonNode} leído
     *
     * La validación de contenido (scores/tendencias válidos) NO es responsabilidad de este
     * método — vive en {@code DatabaseService} y {@code ApiController}.
     */
    public JsonNode ejecutar(String productosJson) {
        boolean useGpuSnapshot = this.useGpu; // snapshot-at-entry, ver javadoc de `useGpu`
        var stderrTail = new java.util.concurrent.ConcurrentLinkedDeque<String>();
        try {
            Path workDir   = Paths.get("").toAbsolutePath();
            Path prodPath  = workDir.resolve("ml_productos.json");
            Path outPath   = workDir.resolve("ml_output.json");
            Path histPath  = workDir.resolve("precio_historico.json");
            Path scriptPath = extraerScript(workDir);
            // Stage-1b category/gender image refinement (ml_pipeline.py `import
            // ml_embeddings`) degrades to text-only (via ml_pipeline.py's own
            // import guard) if this sibling script can't be extracted alongside
            // ml_pipeline.py. Guarded here so a missing/failed extraction never
            // aborts the whole scoring pipeline.
            try {
                extraerEmbeddingsScript(workDir);
            } catch (Exception e) {
                LOG.warn("[ML] No se pudo extraer ml_embeddings.py — stage-1b degrada a solo-texto: {}",
                        e.getMessage());
            }

            Files.writeString(prodPath, productosJson);

            String python = detectarPython();
            if (python == null) {
                LOG.error("[ML] Python no encontrado — pipeline ML NO ejecutado (NOT_RUN)");
                return null;
            }

            LOG.info("[ML] Ejecutando pipeline Python...");
            ProcessBuilder pb = construirProcessBuilderScoring(
                    python, scriptPath, prodPath, outPath, histPath, workDir, useGpuSnapshot);
            Process proc = pb.start();

            Thread.ofVirtual().start(() -> {
                try (var br = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        LOG.info("[ML] {}", line);
                        stderrTail.addLast(line);
                        while (stderrTail.size() > 50) stderrTail.pollFirst();
                    }
                } catch (Exception ignored) {}
            });

            if (!proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                LOG.error("[ML] Timeout tras {}s — NOT_RUN. stderr tail:\n{}",
                        TIMEOUT_SEC, String.join("\n", stderrTail));
                return null;
            }
            if (proc.exitValue() != 0) {
                LOG.error("[ML] Pipeline exit code {} — NOT_RUN. stderr tail:\n{}",
                        proc.exitValue(), String.join("\n", stderrTail));
                return null;
            }
            if (!Files.exists(outPath)) {
                LOG.error("[ML] exit 0 pero no se generó {} — NOT_RUN. stderr tail:\n{}",
                        outPath, String.join("\n", stderrTail));
                return null;
            }

            JsonNode r = MAPPER.readTree(outPath.toFile());
            LOG.info("[ML] Pipeline OK — scores={}, tendencias presente={}",
                    r.path("scores").size(), r.path("tendencias").isObject());
            return r;
        } catch (Exception e) {
            LOG.error("[ML] Excepción en pipeline — NOT_RUN. stderr tail:\n{}",
                    String.join("\n", stderrTail), e);
            return null;
        }
    }

    public TrainingStatus getTrainingStatus() { return trainingStatus.get(); }
    public boolean isTrainingRunning()        { return trainingStatus.get().running(); }

    public BackfillStatus getBackfillStatus()  { return backfillStatus.get(); }

    private Path extraerScript(Path workDir) throws Exception {
        Path dest = workDir.resolve("ml_pipeline.py");
        try (InputStream is = getClass().getResourceAsStream("/ml/ml_pipeline.py")) {
            if (is == null) throw new FileNotFoundException("/ml/ml_pipeline.py no en classpath");
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    /** Extrae ml_train.py al directorio de trabajo */
    private Path extraerTrainScript(Path workDir) throws Exception {
        Path dest = workDir.resolve("ml_train.py");
        try (InputStream is = getClass().getResourceAsStream("/ml/ml_train.py")) {
            if (is != null) {
                Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
            } else if (!Files.exists(dest)) {
                throw new java.io.FileNotFoundException("ml_train.py no encontrado en classpath ni en " + dest);
            }
        }
        return dest;
    }

    /** Serializes concurrent extractions of ml_embeddings.py (see {@link #extraerEmbeddingsScript}). */
    private static final Object EMBEDDINGS_EXTRACT_LOCK = new Object();

    /**
     * Extrae ml_embeddings.py al directorio de trabajo, junto a ml_pipeline.py.
     * Requerido tanto por el stage-1b de refinamiento de imagen del pipeline
     * de scoring ({@code import ml_embeddings}) como por el launcher de
     * backfill ({@link #backfillEmbeddingsEnBackground}) — sin este archivo
     * ambos degradan silenciosamente a solo-texto. Mirror de
     * {@link #extraerTrainScript}: tolera que el archivo ya exista.
     *
     * <p>Called from two independently-schedulable sites ({@link #ejecutar}'s
     * synchronous path and {@link #backfillEmbeddingsEnBackground}'s virtual
     * thread), both writing the same {@code dest}. Publishes the destination
     * ATOMICALLY (write to a unique temp file, then {@code ATOMIC_MOVE} it into
     * place, falling back to {@code REPLACE_EXISTING} if the filesystem doesn't
     * support atomic moves) and serializes the write+move with a private static
     * lock, so a concurrent reader (Python subprocess importing the module)
     * never observes a torn/partial file.</p>
     */
    Path extraerEmbeddingsScript(Path workDir) throws Exception {
        Path dest = workDir.resolve("ml_embeddings.py");
        synchronized (EMBEDDINGS_EXTRACT_LOCK) {
            try (InputStream is = getClass().getResourceAsStream("/ml/ml_embeddings.py")) {
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

    /**
     * Lanza entrenamiento ML en background después del scraping.
     * Solo entrena si no existe modelo o si el modelo tiene más de 24h de antigüedad.
     * Con forceRetrain=true, saltea la verificación de antigüedad y siempre entrena.
     */
    public void entrenarEnBackground(String dbPath, boolean forceRetrain,
                                      boolean withImages, int epochs) {
        boolean useGpuSnapshot = this.useGpu; // snapshot-at-entry, ver javadoc de `useGpu`
        String python = detectarPython();
        if (python == null) { LOG.info("[ML-TRAIN] Python no disponible, saltando entrenamiento"); return; }

        Path workDir = Paths.get("").toAbsolutePath();
        Path modelsDir = workDir.resolve("_models");
        Path textModel = modelsDir.resolve("text_classifier.pkl");
        boolean modelExists = Files.exists(textModel);

        if (forceRetrain) {
            LOG.info("[ML-TRAIN] forceRetrain=true — saltando verificación de antigüedad del modelo.");
        }

        // Si el modelo existe y fue entrenado recientemente (<24h), saltear (a menos que forceRetrain=true)
        if (!forceRetrain && modelExists) {
            try {
                long age = System.currentTimeMillis() - Files.getLastModifiedTime(textModel).toMillis();
                if (age < 24L * 3600 * 1000) {
                    LOG.info("[ML-TRAIN] Modelo reciente ({} horas), saltando re-entrenamiento",
                             age / 3600000);
                    return;
                }
            } catch (Exception ignored) {}
        }

        LOG.info("[ML-TRAIN] ===== Iniciando entrenamiento del modelo =====");
        LOG.info("[ML-TRAIN] Esto puede tomar entre 3 y 40 minutos dependiendo del volumen de datos.");

        Thread.ofVirtual().start(() -> {
            try {
                trainingStatus.set(new TrainingStatus(true, "starting", 0, "",
                        java.time.Instant.now().toString()));

                Path trainScript = extraerTrainScript(workDir);

                var cmd = new java.util.ArrayList<String>();
                cmd.add(python);
                cmd.add(trainScript.toString());
                cmd.add(dbPath);

                boolean forceCpuProbe = forceCpuParaProbes(useGpuSnapshot);
                boolean hasCuda  = useGpuSnapshot && tieneCuda(python, forceCpuProbe);
                boolean hasTorch = hasCuda || tienePytorch(python, forceCpuProbe);
                if (withImages && hasTorch) {
                    cmd.add("--images");
                    cmd.add("--epochs");
                    cmd.add(String.valueOf(epochs));
                    LOG.info("[ML-TRAIN] PyTorch detectado (CUDA:{}) — entrenando texto + imágenes", hasCuda);
                } else if (withImages) {
                    LOG.info("[ML-TRAIN] withImages=true pero PyTorch no disponible — solo texto");
                } else {
                    LOG.info("[ML-TRAIN] Solo entrenamiento de texto");
                }

                ProcessBuilder pb = construirProcessBuilderEntrenamiento(cmd, workDir, useGpuSnapshot);
                Process proc = pb.start();

                // Stderr: logs [TRAIN]
                Thread.ofVirtual().start(() -> {
                    try (var br = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) LOG.info("[ML-TRAIN] {}", line);
                    } catch (Exception ignored) {}
                });

                // Stdout: progress JSON lines + final result — stream en tiempo real
                var sb = new StringBuilder();
                try (var br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append("\n");
                        // Loguear líneas de progreso en tiempo real
                        if (line.startsWith("{") && line.contains("\"pct\"")) {
                            try {
                                var node = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readTree(line);
                                int pct  = node.path("pct").asInt();
                                String ph = node.path("phase").asText("");
                                String msg = node.path("msg").asText("");
                                LOG.info("[ML-TRAIN] [{}] {}% — {}", ph.toUpperCase(), pct, msg);
                                trainingStatus.set(new TrainingStatus(true, ph, pct, msg,
                                        trainingStatus.get().startedAt()));
                            } catch (Exception ignored) {}
                        }
                    }
                }

                boolean withImg = cmd.contains("--images");
                long timeoutMin = withImg ? 180L : 15L;
                boolean finished = proc.waitFor(timeoutMin, TimeUnit.MINUTES);
                if (!finished) {
                    proc.destroyForcibly();
                    LOG.error("[ML-TRAIN] TIMEOUT tras {} min — proceso terminado forzosamente", timeoutMin);
                    trainingStatus.set(new TrainingStatus(false, "timeout", 0, "", null));
                    return;
                }
                int exitCode = proc.exitValue();

                // El último JSON completo es el resultado final
                String out = sb.toString().trim();
                // Buscar el último JSON completo (línea que empieza con '{' y tiene 'status')
                String finalJson = "";
                for (String l : out.split("\n")) {
                    if (l.startsWith("{") && l.contains("status")) finalJson = l;
                }
                if (exitCode == 0) {
                    LOG.info("[ML-TRAIN] ✓ ENTRENAMIENTO COMPLETADO");
                    if (!finalJson.isBlank()) LOG.info("[ML-TRAIN] Resultado: {}", finalJson);
                    // Auto-aplicar modelo: re-ejecutar pipeline ML sobre datos actuales
                    LOG.info("[ML-TRAIN] Aplicando modelo a datos actuales...");
                    aplicarModeloActual();
                    trainingStatus.set(TrainingStatus.idle());
                } else {
                    LOG.warn("[ML-TRAIN] Proceso terminó con código {}", exitCode);
                    trainingStatus.set(new TrainingStatus(false, "error", 0, "exit " + exitCode, null));
                }
            } catch (Exception e) {
                LOG.warn("[ML-TRAIN] Error inesperado: {}", e.getMessage());
                trainingStatus.set(new TrainingStatus(false, "error", 0, e.getMessage(), null));
            }
        });
    }

    public void entrenarEnBackground(String dbPath, boolean forceRetrain) {
        entrenarEnBackground(dbPath, forceRetrain, false, 8);
    }

    public void entrenarEnBackground(String dbPath) {
        entrenarEnBackground(dbPath, false, false, 8);
    }

    /**
     * Test seam (package-private, pure): decides whether text re-training
     * should be skipped for freshness — mirrors the guard inlined in
     * {@link #entrenarEnBackground} ({@code modelExists && !forceRetrain &&
     * age < 24h}). Extracted so the decision itself is directly unit-testable
     * without touching the filesystem, and reused by the T5.4 sequencing
     * entrypoint ({@link #construirIndiceVisualEnBackground}).
     */
    boolean debeSaltearEntrenamientoPorFrescura(boolean modelExists, boolean forceRetrain,
            long edadModeloMillis) {
        if (forceRetrain || !modelExists) return false;
        return edadModeloMillis < 24L * 3600 * 1000;
    }

    /**
     * Test seam (package-private, pure): parses a subprocess stdout progress
     * JSON line ({@code {"pct":N,"msg":"..."}}, own {@code "phase"} key
     * ignored — the caller supplies the macro {@code fase} label instead) into
     * an updated {@link TrainingStatus}, preserving {@code startedAt} from
     * {@code previo}. Returns {@code previo} unchanged when the line isn't a
     * recognizable progress line (null, non-JSON, missing {@code "pct"}, or
     * malformed). Backs the T5.4 sequencing entrypoint
     * ({@link #construirIndiceVisualEnBackground}), which tags every line
     * from the text-training subprocess with {@code fase="training"} and
     * every line from the embeddings-backfill subprocess with
     * {@code fase="embedding"} — both writing into the SAME
     * {@link #trainingStatus} object, so a single poll surface can
     * distinguish the two phases of "Construir índice visual".
     */
    TrainingStatus parsearLineaProgreso(String line, String fase, TrainingStatus previo) {
        if (line == null || !line.startsWith("{") || !line.contains("\"pct\"")) return previo;
        try {
            var node = MAPPER.readTree(line);
            int pct = node.path("pct").asInt();
            String msg = node.path("msg").asText("");
            return new TrainingStatus(true, fase, pct, msg, previo.startedAt());
        } catch (Exception e) {
            return previo;
        }
    }

    /**
     * Sequencing entrypoint for "Construir índice visual" (PR6's future
     * {@code POST /api/ml/entrenar} handler, T6.2): runs text re-training
     * FIRST, then the embeddings backfill, on ONE background thread — both
     * phases reporting into the SAME {@link #trainingStatus} object, tagged
     * with a distinct macro {@code phase} ({@code "training"} vs
     * {@code "embedding"}) via {@link #parsearLineaProgreso}, so a single
     * poll surface (future {@code GET /api/ml/estado}, T6.3/T6.4) can tell
     * the two stages apart. Mirrors {@link #entrenarEnBackground} and
     * {@link #backfillEmbeddingsEnBackground}'s executor/status/progress
     * pattern (same {@link TrainingStatus} record, same
     * {@code construirProcessBuilderEntrenamiento}/
     * {@code construirProcessBuilderBackfill} seams) rather than reusing
     * their thread bodies directly, so this new sequencing path can never
     * regress either standalone entrypoint. Never throws — any failure in
     * either phase degrades to a logged no-op and the sequence still
     * attempts the next phase (backfill still runs even if text-training
     * was skipped or failed).
     *
     * <p>RESI-003: each phase now reports success/failure back to this
     * entrypoint. The {@code finally} block only resets {@link #trainingStatus}
     * to {@link TrainingStatus#idle()} when BOTH phases succeeded
     * ({@link #debeResetearAIdleTrasSecuencia}) — a phase failure already wrote
     * a durable {@code phase="error"} terminal state (via
     * {@link #marcarFalloIndiceVisual}, mirroring {@link #entrenarEnBackground}'s
     * existing error-state pattern at its {@code exit != 0}/exception branches)
     * that must survive so {@code /api/ml/estado} polling can observe it
     * instead of seeing a falsely-idle run.</p>
     */
    public void construirIndiceVisualEnBackground(String dbPath, boolean forceRetrainTexto,
            boolean withImages, int epochs, boolean forceBackfillEmbeddings) {
        boolean useGpuSnapshot = this.useGpu; // snapshot-at-entry, ver javadoc de `useGpu`
        String python = detectarPython();
        if (python == null) {
            LOG.info("[ML-INDEX] Python no disponible, saltando construcción de índice visual");
            return;
        }

        Path workDir = Paths.get("").toAbsolutePath();

        Thread.ofVirtual().start(() -> ejecutarSecuenciaIndiceVisual(python, workDir, dbPath,
                forceRetrainTexto, withImages, epochs, forceBackfillEmbeddings, useGpuSnapshot));
    }

    /**
     * Test seam (package-private): the synchronous core of
     * {@link #construirIndiceVisualEnBackground} — same sequencing logic the
     * public async entrypoint delegates to (via {@code Thread.ofVirtual()}),
     * extracted so a test can invoke it directly with an explicit
     * {@code python} executable (bypassing {@code detectarPython()}'s
     * real-environment scan) and assert on the final {@link #trainingStatus}
     * without polling a background thread. Production callers only ever
     * reach this through the public entrypoint.
     *
     * <p>FIXV-001 fix (4R correction round, fix-delta escalation): the
     * inter-phase {@code trainingStatus.set(new TrainingStatus(true,
     * "embedding", ...))} — previously unconditional — now only fires when
     * {@code trainingOk} is {@code true}, so a training failure's durable
     * {@code phase="error"} status (already written by
     * {@link #ejecutarFaseEntrenamientoSecuenciada} via
     * {@link #marcarFalloIndiceVisual}) isn't immediately overwritten with a
     * false "starting embedding, running" status before backfill even
     * begins. That alone isn't sufficient, though: backfill's OWN progress
     * reporting (via {@link #esperarConDrain}'s {@link #parsearLineaProgreso}
     * calls) still legitimately flips {@link #trainingStatus} to
     * running=true/phase="embedding" while it's actually running — accurate,
     * desired behavior. The bug was specifically about the FINAL state after
     * backfill completes: if training failed but backfill then SUCCEEDS,
     * backfill's success path writes no terminal status of its own (only its
     * failure branches call {@code marcarFalloIndiceVisual}), and
     * {@link #debeResetearAIdleTrasSecuencia} correctly refuses to reset to
     * idle for this combo (only true when BOTH succeed) — so without the
     * explicit re-write below, the status would stay permanently stuck at
     * running=true/phase="embedding" after this thread dies, silently
     * erasing the training failure and leaving an {@code /api/ml/estado}
     * poller seeing "in progress" forever. The
     * {@code !trainingOk && backfillOk} branch re-asserts a durable,
     * non-running error state whose message reflects both outcomes.</p>
     */
    void ejecutarSecuenciaIndiceVisual(String python, Path workDir, String dbPath,
            boolean forceRetrainTexto, boolean withImages, int epochs, boolean forceBackfillEmbeddings,
            boolean useGpuSnapshot) {
        boolean trainingOk = false;
        boolean backfillOk = false;
        try {
            trainingStatus.set(new TrainingStatus(true, "training", 0, "",
                    java.time.Instant.now().toString()));
            trainingOk = ejecutarFaseEntrenamientoSecuenciada(python, workDir, dbPath, forceRetrainTexto,
                    withImages, epochs, useGpuSnapshot);

            if (trainingOk) {
                trainingStatus.set(new TrainingStatus(true, "embedding", 0, "",
                        trainingStatus.get().startedAt()));
            }
            backfillOk = ejecutarFaseBackfillSecuenciada(python, workDir, dbPath, forceBackfillEmbeddings,
                    useGpuSnapshot);

            if (!trainingOk && backfillOk) {
                marcarFalloIndiceVisual("training",
                        "entrenamiento falló, backfill completado igual");
            }
        } catch (Exception e) {
            LOG.warn("[ML-INDEX] Error inesperado en la secuencia de construcción de índice: {}",
                    e.getMessage());
            marcarFalloIndiceVisual("sequencing", e.getMessage());
        } finally {
            if (debeResetearAIdleTrasSecuencia(trainingOk, backfillOk)) {
                trainingStatus.set(TrainingStatus.idle());
            }
        }
    }

    public void construirIndiceVisualEnBackground(String dbPath, boolean forceRetrainTexto,
            boolean forceBackfillEmbeddings) {
        construirIndiceVisualEnBackground(dbPath, forceRetrainTexto, false, 8, forceBackfillEmbeddings);
    }

    /** Outcome of {@link #esperarConDrain}: whether the process finished within
     * the deadline, and its exit code when it did ({@code -1} when it didn't). */
    record ResultadoEspera(boolean finished, int exitCode) {}

    /**
     * Test seam (package-private): RESI-001 fix. Drains {@code proc}'s stdout
     * (tagging every parsed progress line into {@link #trainingStatus} via
     * {@link #parsearLineaProgreso} under macro-phase {@code fase}, and handing
     * the raw line to the optional {@code stdoutLineHandler}) and stderr (via
     * the optional {@code stderrLineHandler}) on separate virtual threads,
     * while THIS thread blocks ONLY on {@code proc.waitFor(timeout, unit)} —
     * never on a blocking stdout {@code readLine()} loop.
     *
     * <p>Previously (both sequencing phase methods) stdout was read to EOF
     * with a blocking {@code readLine()} loop BEFORE {@code waitFor} was ever
     * reached, so a child that stayed alive with stdout open but silent (e.g.
     * a cold model-weight download inside {@code _load_model} that emits no
     * progress) blocked the calling thread forever and the timeout was never
     * evaluated. Draining on separate threads keeps {@code waitFor}'s deadline
     * reachable regardless of the child's stdout behavior.</p>
     *
     * <p>On timeout, force-kills {@code proc} and returns immediately WITHOUT
     * waiting for the drain threads — a hung child's streams may never close
     * on their own. On a normal exit, joins both drain threads with a short
     * bounded wait ({@link #joinDrainThread}) so the last buffered lines are
     * captured before the caller inspects any counters derived from the line
     * handlers (e.g. backfill's degraded-row count, RESI-002).</p>
     *
     * <p>{@code timeout}/{@code unit} are parameterized (rather than a
     * hardcoded {@code 180L} MINUTES) purely so a test can inject a short
     * deadline against a synthetic long-lived, silent child process without
     * waiting 180 real minutes; production call sites always pass
     * {@code MINUTES}.</p>
     */
    ResultadoEspera esperarConDrain(Process proc, long timeout, TimeUnit unit, String fase,
            java.util.function.Consumer<String> stdoutLineHandler,
            java.util.function.Consumer<String> stderrLineHandler) {
        Thread stdoutThread = Thread.ofVirtual().start(() -> {
            try (var br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    trainingStatus.set(parsearLineaProgreso(line, fase, trainingStatus.get()));
                    if (stdoutLineHandler != null) stdoutLineHandler.accept(line);
                }
            } catch (Exception ignored) {}
        });
        Thread stderrThread = Thread.ofVirtual().start(() -> {
            try (var br = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (stderrLineHandler != null) stderrLineHandler.accept(line);
                }
            } catch (Exception ignored) {}
        });

        boolean finished;
        try {
            finished = proc.waitFor(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            proc.destroyForcibly();
            return new ResultadoEspera(false, -1);
        }
        joinDrainThread(stdoutThread);
        joinDrainThread(stderrThread);
        return new ResultadoEspera(true, proc.exitValue());
    }

    /** Bounded join for a stdout/stderr drain thread after {@code waitFor()}
     * returns — the child's streams close once the process exits, so the
     * reader thread reaches EOF and finishes almost immediately; this join is
     * just to avoid racing the last couple of buffered lines before the
     * caller reads final counters. Never blocks indefinitely. */
    private void joinDrainThread(Thread t) {
        try {
            t.join(java.time.Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Marker text ml_embeddings.py's {@code backfill()} logs to stderr, once
     * per row, when {@code classify()} degraded to its no-signal sentinel and
     * that row's visual attrs were skipped-not-persisted (see
     * ml_embeddings.py's per-row skip branch, ~line 836). Used by
     * {@link #esBackfillDegradado} detection (RESI-002) — counted, never
     * parsed further. */
    static final String BACKFILL_SIN_SENAL_MARKER = "no visual signal for";

    private static final java.util.regex.Pattern PROGRESO_PROCESADAS_PATTERN =
            java.util.regex.Pattern.compile("^(\\d+)/(\\d+) — ");

    /**
     * Test seam (package-private, pure): RESI-002. Extracts the running
     * "processed" count from a backfill per-row progress line shaped
     * {@code {"phase":"embedding","pct":N,"msg":"{processed}/{total} — {url}"}}
     * (see ml_embeddings.py's {@code backfill()} per-row {@code _emit_progress}
     * call). Returns {@code -1} when {@code line} doesn't match that shape
     * (non-JSON, missing {@code pct}, or a msg like "sin productos
     * pendientes"/"backfill completo"/an error message), so callers can tell
     * "no count observed yet" apart from a real {@code 0}.
     */
    int extraerProcesadasDeLineaProgreso(String line) {
        if (line == null || !line.startsWith("{") || !line.contains("\"pct\"")) return -1;
        try {
            var node = MAPPER.readTree(line);
            String msg = node.path("msg").asText("");
            var m = PROGRESO_PROCESADAS_PATTERN.matcher(msg);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Test seam (package-private, pure): RESI-002 degraded-backfill detector.
     * A backfill "succeeds" (exit 0) even when the model never loaded and
     * every processed row hit ml_embeddings.py's classify()-degrades-to-""
     * skip branch (logged via {@link #BACKFILL_SIN_SENAL_MARKER} instead of
     * persisted) — {@code ml_embeddings.py} always {@code sys.exit(0)} for the
     * {@code backfill} subcommand regardless. Returns {@code true} only when
     * at least one row was actually processed AND every processed row was
     * skipped — a partially-degraded run (some rows persisted, some skipped)
     * is NOT reported as degraded, since it still added real signal.
     */
    boolean esBackfillDegradado(int filasProcesadas, int filasSinSenal) {
        return filasProcesadas > 0 && filasSinSenal >= filasProcesadas;
    }

    /**
     * Test seam (package-private): RESI-003. Writes a durable
     * {@code phase="error"} terminal {@link TrainingStatus} — mirrors
     * {@link #entrenarEnBackground}'s existing error-state pattern
     * ({@code new TrainingStatus(false, "error", 0, "exit " + exitCode, null)}).
     * Called by both sequenced phase methods on timeout, non-zero exit,
     * RESI-002 degraded-backfill detection, and unexpected exceptions, so
     * {@code /api/ml/estado} polling can observe the failure instead of the
     * entrypoint's {@code finally} silently resetting to idle over it (see
     * {@link #debeResetearAIdleTrasSecuencia}).
     */
    private void marcarFalloIndiceVisual(String fase, String motivo) {
        trainingStatus.set(new TrainingStatus(false, "error", 0,
                "[" + fase + "] " + (motivo != null ? motivo : ""), null));
    }

    /**
     * Test seam (package-private, pure): RESI-003 decision — whether
     * {@link #construirIndiceVisualEnBackground}'s {@code finally} block may
     * reset {@link #trainingStatus} back to {@link TrainingStatus#idle()}.
     * Only {@code true} when BOTH phases reported success; if either phase
     * failed, it already wrote a durable {@code phase="error"} terminal state
     * (via {@link #marcarFalloIndiceVisual}) that {@code /api/ml/estado}
     * polling must be able to observe — resetting to idle in that case would
     * silently erase the failure and make it indistinguishable from success.
     */
    boolean debeResetearAIdleTrasSecuencia(boolean trainingOk, boolean backfillOk) {
        return trainingOk && backfillOk;
    }

    /** Fase "training" de {@link #construirIndiceVisualEnBackground}. Nunca lanza.
     * @return {@code true} on success (including a fresh-model skip); {@code false}
     * on timeout, non-zero exit, or an unexpected exception — in every failure
     * case a durable error state is written via {@link #marcarFalloIndiceVisual}. */
    boolean ejecutarFaseEntrenamientoSecuenciada(String python, Path workDir, String dbPath,
            boolean forceRetrain, boolean withImages, int epochs, boolean useGpuSnapshot) {
        try {
            Path modelsDir = workDir.resolve("_models");
            Path textModel = modelsDir.resolve("text_classifier.pkl");
            boolean modelExists = Files.exists(textModel);
            long edadMillis = 0L;
            if (modelExists) {
                try {
                    edadMillis = System.currentTimeMillis() - Files.getLastModifiedTime(textModel).toMillis();
                } catch (Exception ignored) {}
            }

            if (debeSaltearEntrenamientoPorFrescura(modelExists, forceRetrain, edadMillis)) {
                LOG.info("[ML-INDEX] [TRAINING] Modelo reciente ({} horas) — saltando re-entrenamiento",
                        edadMillis / 3600000);
                return true;
            }

            Path trainScript = extraerTrainScript(workDir);
            var cmd = new java.util.ArrayList<String>();
            cmd.add(python);
            cmd.add(trainScript.toString());
            cmd.add(dbPath);

            boolean forceCpuProbe = forceCpuParaProbes(useGpuSnapshot);
            boolean hasCuda  = useGpuSnapshot && tieneCuda(python, forceCpuProbe);
            boolean hasTorch = hasCuda || tienePytorch(python, forceCpuProbe);
            if (withImages && hasTorch) {
                cmd.add("--images");
                cmd.add("--epochs");
                cmd.add(String.valueOf(epochs));
            }

            ProcessBuilder pb = construirProcessBuilderEntrenamiento(cmd, workDir, useGpuSnapshot);
            Process proc = pb.start();

            boolean withImg = cmd.contains("--images");
            long timeoutMin = withImg ? 180L : 15L;
            ResultadoEspera espera = esperarConDrain(proc, timeoutMin, TimeUnit.MINUTES, "training",
                    null, line -> LOG.info("[ML-INDEX] [TRAINING] {}", line));

            if (!espera.finished()) {
                LOG.error("[ML-INDEX] [TRAINING] TIMEOUT tras {} min — proceso terminado forzosamente",
                        timeoutMin);
                marcarFalloIndiceVisual("training", "timeout tras " + timeoutMin + " min");
                return false;
            }
            if (espera.exitCode() == 0) {
                LOG.info("[ML-INDEX] [TRAINING] ✓ completado");
                aplicarModeloActual();
                return true;
            }
            LOG.warn("[ML-INDEX] [TRAINING] Proceso terminó con código {}", espera.exitCode());
            marcarFalloIndiceVisual("training", "exit " + espera.exitCode());
            return false;
        } catch (Exception e) {
            LOG.warn("[ML-INDEX] [TRAINING] Error inesperado: {}", e.getMessage());
            marcarFalloIndiceVisual("training", e.getMessage());
            return false;
        }
    }

    /** Fase "embedding" de {@link #construirIndiceVisualEnBackground}. Nunca lanza.
     * @return {@code true} on a genuinely successful backfill; {@code false} on
     * timeout, non-zero exit, RESI-002 degraded-run detection, or an unexpected
     * exception — in every failure case a durable error state is written via
     * {@link #marcarFalloIndiceVisual}. */
    boolean ejecutarFaseBackfillSecuenciada(String python, Path workDir, String dbPath,
            boolean force, boolean useGpuSnapshot) {
        try {
            Path scriptPath = extraerEmbeddingsScript(workDir);
            ProcessBuilder pb = construirProcessBuilderBackfill(
                    python, scriptPath.toString(), dbPath, force, useGpuSnapshot);
            Process proc = pb.start();

            var filasProcesadas = new java.util.concurrent.atomic.AtomicInteger(-1);
            var filasSinSenal = new java.util.concurrent.atomic.AtomicInteger(0);

            long timeoutMin = 180L;
            ResultadoEspera espera = esperarConDrain(proc, timeoutMin, TimeUnit.MINUTES, "embedding",
                    line -> {
                        int procesadas = extraerProcesadasDeLineaProgreso(line);
                        if (procesadas >= 0) filasProcesadas.set(procesadas);
                    },
                    line -> {
                        LOG.info("[ML-INDEX] [EMBEDDING] {}", line);
                        if (line.contains(BACKFILL_SIN_SENAL_MARKER)) filasSinSenal.incrementAndGet();
                    });

            if (!espera.finished()) {
                LOG.error("[ML-INDEX] [EMBEDDING] TIMEOUT tras {} min — proceso terminado forzosamente",
                        timeoutMin);
                marcarFalloIndiceVisual("embedding", "timeout tras " + timeoutMin + " min");
                return false;
            }
            if (espera.exitCode() != 0) {
                LOG.warn("[ML-INDEX] [EMBEDDING] Proceso terminó con código {}", espera.exitCode());
                marcarFalloIndiceVisual("embedding", "exit " + espera.exitCode());
                return false;
            }
            if (esBackfillDegradado(filasProcesadas.get(), filasSinSenal.get())) {
                LOG.warn("[ML-INDEX] [EMBEDDING] backfill degradado — modelo no disponible, {} de {} filas sin señal visual",
                        filasSinSenal.get(), filasProcesadas.get());
                marcarFalloIndiceVisual("embedding", "degradado: modelo no disponible");
                return false;
            }
            LOG.info("[ML-INDEX] [EMBEDDING] ✓ completado");
            return true;
        } catch (Exception e) {
            LOG.warn("[ML-INDEX] [EMBEDDING] Error inesperado: {}", e.getMessage());
            marcarFalloIndiceVisual("embedding", e.getMessage());
            return false;
        }
    }

    /**
     * Lanza el backfill de atributos visuales ({@code ml_embeddings.py backfill})
     * en background. Ver el docstring de {@code backfill()} en ml_embeddings.py
     * (~línea 718), que nombra explícitamente este método como su contraparte Java.
     * Nunca lanza excepciones — cualquier fallo degrada a un no-op logueado.
     */
    public void backfillEmbeddingsEnBackground(String dbPath, boolean force) {
        boolean useGpuSnapshot = this.useGpu; // snapshot-at-entry, ver javadoc de `useGpu`
        try {
            String python = detectarPython();
            if (python == null) {
                LOG.info("[ML-BACKFILL] Python no disponible, saltando backfill de embeddings");
                return;
            }

            Path workDir = Paths.get("").toAbsolutePath();

            Thread.ofVirtual().start(() -> {
                try {
                    backfillStatus.set(new BackfillStatus(true, 0, "",
                            java.time.Instant.now().toString()));

                    Path scriptPath = extraerEmbeddingsScript(workDir);

                    ProcessBuilder pb = construirProcessBuilderBackfill(
                            python, scriptPath.toString(), dbPath, force, useGpuSnapshot);
                    Process proc = pb.start();

                    // RESI-002: counters for degraded-backfill detection (model never
                    // loaded, every processed row skipped-not-persisted — exit code is
                    // still 0, see esBackfillDegradado's javadoc). Populated by the
                    // existing stdout/stderr read loops below — no change to their
                    // blocking read-before-waitFor structure (that latent RESI-001
                    // pattern is explicitly out of scope for this pre-existing entrypoint).
                    var filasProcesadas = new java.util.concurrent.atomic.AtomicInteger(-1);
                    var filasSinSenal = new java.util.concurrent.atomic.AtomicInteger(0);

                    // Stderr: logs [ML-BACKFILL]
                    Thread stderrThread = Thread.ofVirtual().start(() -> {
                        try (var br = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                LOG.info("[ML-BACKFILL] {}", line);
                                if (line.contains(BACKFILL_SIN_SENAL_MARKER)) filasSinSenal.incrementAndGet();
                            }
                        } catch (Exception ignored) {}
                    });

                    // Stdout: progress JSON lines — {"phase":"embedding","pct":N,"msg":"..."}
                    try (var br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.startsWith("{") && line.contains("\"pct\"")) {
                                try {
                                    var node = MAPPER.readTree(line);
                                    int pct   = node.path("pct").asInt();
                                    String ph = node.path("phase").asText("");
                                    String msg = node.path("msg").asText("");
                                    LOG.info("[ML-BACKFILL] [{}] {}% — {}", ph.toUpperCase(), pct, msg);
                                    backfillStatus.set(new BackfillStatus(true, pct, msg,
                                            backfillStatus.get().startedAt()));
                                    int procesadas = extraerProcesadasDeLineaProgreso(line);
                                    if (procesadas >= 0) filasProcesadas.set(procesadas);
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    // Full-catalog download + embedding pass, generous timeout — same as
                    // the `--images` training path (see entrenarEnBackground).
                    long timeoutMin = 180L;
                    boolean finished = proc.waitFor(timeoutMin, TimeUnit.MINUTES);
                    if (!finished) {
                        proc.destroyForcibly();
                        LOG.error("[ML-BACKFILL] TIMEOUT tras {} min — proceso terminado forzosamente", timeoutMin);
                        backfillStatus.set(BackfillStatus.idle());
                        return;
                    }
                    // Brief bounded join so the stderr thread (racing the stdout loop
                    // above, which already blocked until the child's stdout closed at
                    // exit) has a moment to flush its last buffered lines before the
                    // degraded-row count below is read.
                    joinDrainThread(stderrThread);

                    int exitCode = proc.exitValue();
                    if (exitCode == 0) {
                        if (esBackfillDegradado(filasProcesadas.get(), filasSinSenal.get())) {
                            LOG.warn("[ML-BACKFILL] backfill degradado — modelo no disponible, {} de {} filas sin señal visual",
                                    filasSinSenal.get(), filasProcesadas.get());
                        } else {
                            LOG.info("[ML-BACKFILL] ✓ BACKFILL COMPLETADO");
                        }
                    } else {
                        LOG.warn("[ML-BACKFILL] Proceso terminó con código {}", exitCode);
                    }
                    backfillStatus.set(BackfillStatus.idle());
                } catch (Exception e) {
                    LOG.warn("[ML-BACKFILL] Error inesperado: {}", e.getMessage());
                    backfillStatus.set(BackfillStatus.idle());
                }
            });
        } catch (Exception e) {
            LOG.warn("[ML-BACKFILL] Error inesperado al lanzar el backfill: {}", e.getMessage());
        }
    }

    public void backfillEmbeddingsEnBackground(String dbPath) {
        backfillEmbeddingsEnBackground(dbPath, false);
    }

    private void aplicarModeloActual() {
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:3000/api/ml/aplicar"))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                .timeout(java.time.Duration.ofSeconds(5))
                .build();
            client.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.discarding());
            LOG.info("[ML-TRAIN] Pipeline ML re-disparado automaticamente");
        } catch (Exception e) {
            LOG.debug("[ML-TRAIN] Auto-apply skipped: {}", e.getMessage());
        }
    }

    /**
     * Test seam (package-private): computes the {@code forceCpu} argument
     * that {@link #entrenarEnBackground} passes into the {@code tieneCuda}/
     * {@code tienePytorch} probes, from the {@code useGpuSnapshot} flag
     * ({@code true} = GPU allowed). The probes' {@code forceCpu} has the
     * INVERSE polarity ({@code true} = force CPU on that probe subprocess
     * via {@code CUDA_VISIBLE_DEVICES=-1}), so this must return
     * {@code !useGpuSnapshot}. Extracted so the polarity itself is a named,
     * directly testable unit instead of an inline expression — a previous
     * version passed {@code useGpuSnapshot} straight through, which forced
     * CPU on the CUDA probe whenever GPU was enabled and defeated detection.
     */
    boolean forceCpuParaProbes(boolean useGpuSnapshot) {
        return !useGpuSnapshot;
    }

    private boolean tienePytorch(String python, boolean forceCpu) {
        try {
            ProcessBuilder pb = construirProcessBuilderProbe(python, "import torch; print('ok')", forceCpu);
            Process proc = pb.start();
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return false;
            }
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            return out.contains("ok");
        } catch (Exception e) { return false; }
    }

    private boolean tieneCuda(String python, boolean forceCpu) {
        try {
            ProcessBuilder pb = construirProcessBuilderProbe(python,
                    "import torch; print('ok' if torch.cuda.is_available() else 'no')", forceCpu);
            Process proc = pb.start();
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return false;
            }
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            return out.contains("ok");
        } catch (Exception e) { return false; }
    }

    // ── Test seams (package-private): construyen los ProcessBuilder de cada
    // subproceso Python SIN iniciarlos, para poder verificar en tests el env
    // var CUDA_VISIBLE_DEVICES sin depender de un intérprete Python real. ──

    /** ProcessBuilder del pipeline de scoring/inferencia ({@link #ejecutar}). */
    ProcessBuilder construirProcessBuilderScoring(String python, Path scriptPath, Path prodPath,
            Path outPath, Path histPath, Path workDir, boolean useGpuSnapshot) {
        ProcessBuilder pb = new ProcessBuilder(
                python, scriptPath.toString(),
                prodPath.toString(), outPath.toString(), histPath.toString());
        pb.redirectErrorStream(false);
        pb.directory(workDir.toFile());
        // Paridad con el path de entrenamiento: UTF-8 evita el mojibake en
        // los logs (estad�sticas → estadísticas) y PYTHONUNBUFFERED hace que
        // stderr se vacíe línea a línea, así un crash nativo (ej. exit
        // 0xC0000409) no se traga las últimas líneas y podemos ver dónde murió.
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");
        pb.environment().put("PYTHONUNBUFFERED", "1");
        if (!useGpuSnapshot) {
            pb.environment().put("CUDA_VISIBLE_DEVICES", "-1");
        }
        return pb;
    }

    /** ProcessBuilder del entrenamiento ({@link #entrenarEnBackground}). */
    ProcessBuilder construirProcessBuilderEntrenamiento(java.util.List<String> cmd, Path workDir,
            boolean useGpuSnapshot) {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(false);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");
        if (!useGpuSnapshot) {
            pb.environment().put("CUDA_VISIBLE_DEVICES", "-1");
        }
        return pb;
    }

    /** ProcessBuilder del backfill de embeddings ({@link #backfillEmbeddingsEnBackground}). */
    ProcessBuilder construirProcessBuilderBackfill(String python, String scriptPath, String dbPath,
            boolean force, boolean useGpuSnapshot) {
        var cmd = new java.util.ArrayList<String>();
        cmd.add(python);
        cmd.add(scriptPath);
        cmd.add("backfill");
        cmd.add(dbPath);
        if (force) cmd.add("--force");
        if (!useGpuSnapshot) cmd.add("--no-gpu");

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(Paths.get("").toAbsolutePath().toFile())
                .redirectErrorStream(false);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");
        pb.environment().put("PYTHONUNBUFFERED", "1");
        pb.environment().put("HF_HOME", hfHomeParaDb(dbPath));
        if (!useGpuSnapshot) {
            pb.environment().put("CUDA_VISIBLE_DEVICES", "-1");
        }
        return pb;
    }

    /**
     * Directorio de pesos Marqo pre-descargados por el instalador para el
     * subproceso de backfill (T5.3). MUST mirror the installer's pinning
     * ({@code INSTALAR_Y_CORRER.bat} step 3g: {@code HF_HOME=%ROOT%\_models\marqo})
     * and {@code ml_embeddings.py}'s own fallback ({@code _default_hf_home(db_path)}:
     * {@code Path(db_path).resolve().parent / "_models" / "marqo"}) — same
     * directory shape, computed the same way (parent of the resolved DB
     * path), so a real run always hits the pre-warmed cache instead of
     * re-downloading ~300MB.
     */
    static String hfHomeParaDb(String dbPath) {
        return Paths.get(dbPath).toAbsolutePath().getParent()
                .resolve("_models").resolve("marqo").toString();
    }

    /** ProcessBuilder de los probes {@code tieneCuda}/{@code tienePytorch}. */
    ProcessBuilder construirProcessBuilderProbe(String python, String codigoPython, boolean forceCpu) {
        ProcessBuilder pb = new ProcessBuilder(python, "-c", codigoPython)
                .redirectErrorStream(true);
        if (forceCpu) {
            pb.environment().put("CUDA_VISIBLE_DEVICES", "-1");
        }
        return pb;
    }

    private String detectarPython() {
        // 1. Prioridad: -DPYTHON_EXE pasado por el bat
        String sysPy = System.getProperty("PYTHON_EXE");
        if (sysPy != null && !sysPy.isBlank() && new java.io.File(sysPy).exists()) return sysPy;

        // 2. Buscar python portable en _tools (relativo al working dir)
        String wd = System.getProperty("user.dir");
        String[] relatives = {
            wd + "/../_tools/python/python.exe",
            wd + "/_tools/python/python.exe",
            wd + "/../_tools/python/python3"
        };
        for (String path : relatives) {
            if (new java.io.File(path).exists()) return path;
        }

        // 3. Python del sistema
        for (String cmd : new String[]{"python3","python"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return null;
    }
}
