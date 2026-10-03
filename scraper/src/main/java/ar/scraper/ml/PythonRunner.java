package ar.scraper.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ar.scraper.scrape.StatusEvent;
import ar.scraper.scrape.StatusEvents;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;

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

    public record BackfillStatus(boolean running, int pct, String msg, String startedAt) {
        public static BackfillStatus idle() {
            return new BackfillStatus(false, 0, "", null);
        }
    }

    private final java.util.concurrent.atomic.AtomicReference<BackfillStatus> backfillStatus =
        new java.util.concurrent.atomic.AtomicReference<>(BackfillStatus.idle());

    private final StatusEvents bus;

    public PythonRunner() {
        this(StatusEvents.NONE);
    }

    @Autowired
    public PythonRunner(StatusEvents bus) {
        this.bus = bus;
    }

    private void setTraining(TrainingStatus nuevo) {
        trainingStatus.set(nuevo);
        publicar(nuevo);
    }

    private void setBackfill(BackfillStatus nuevo) {
        backfillStatus.set(nuevo);
        bus.publish(new StatusEvent.MlStatus(StatusEvent.MlStatus.Kind.BACKFILL, nuevo.running(),
                "", nuevo.pct(), nuevo.msg(), nuevo.startedAt()));
    }

    private void publicar(TrainingStatus t) {
        bus.publish(new StatusEvent.MlStatus(StatusEvent.MlStatus.Kind.TRAINING, t.running(),
                t.phase(), t.pct(), t.msg(), t.startedAt()));
    }

    /**
     * No es control de recursos, es integridad de datos. Dos corridas concurrentes se escriben los
     * archivos entre sí, y ninguna falla ruidosamente: la segunda lee el input de la primera o
     * publica un output mezclado.
     */
    private final java.util.concurrent.atomic.AtomicBoolean scoringEnCurso =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    public boolean isScoringEnCurso() { return scoringEnCurso.get(); }

    /**
     * Ejecuta {@code cuerpo} con el slot de scoring reservado, o devuelve {@code null} sin correrlo
     * si ya había una corrida en vuelo.
     */
    <T> T conReservaDeScoring(java.util.function.Supplier<T> cuerpo) {
        if (!scoringEnCurso.compareAndSet(false, true)) {
            LOG.warn("[ML] Ya hay una corrida de scoring en vuelo — esta solicitud se descarta "
                    + "(comparten ml_productos.json/ml_output.json en el cwd)");
            return null;
        }
        try {
            return cuerpo.get();
        } finally {
            scoringEnCurso.set(false);
        }
    }

    /**
     * {@code true} (default) = comportamiento actual sin cambios (probe CUDA si está disponible).
     */
    private volatile boolean useGpu = true;

    public void setUseGpu(boolean v) { this.useGpu = v; }
    public boolean isUseGpu() { return useGpu; }

    /**
     * Ejecuta el pipeline ML con contrato de 3 estados: - NOT_RUN (Python no encontrado, timeout,
     * exit!=0, sin output, excepción) → retorna {@code null} - EMPTY/VALID (proceso OK, output
     * parseado tal cual) → retorna el {@link JsonNode} leído.
     */
    public JsonNode ejecutar(String productosJson) {
        return conReservaDeScoring(() -> ejecutarScoring(productosJson));
    }

    private JsonNode ejecutarScoring(String productosJson) {
        boolean useGpuSnapshot = this.useGpu;
        var stderrTail = new java.util.concurrent.ConcurrentLinkedDeque<String>();
        try {
            Path workDir   = Paths.get("").toAbsolutePath();
            Path prodPath  = workDir.resolve("ml_productos.json");
            Path outPath   = workDir.resolve("ml_output.json");
            Path histPath  = workDir.resolve("precio_historico.json");
            Path scriptPath = extraerScript(workDir);
            // Stage-1b category/gender image refinement (ml_pipeline.py `import ml_embeddings`)
            // degrades to text-only (via ml_pipeline.py's own import guard) if this sibling script
            // can't be extracted alongside ml_pipeline.py.
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

            drenarEnHilo(proc.getErrorStream(), line -> {
                LOG.info("[ML] {}", line);
                stderrTail.addLast(line);
                while (stderrTail.size() > 50) stderrTail.pollFirst();
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
        return MlScriptExtractor.extraerPipeline(workDir);
    }

    private Path extraerTrainScript(Path workDir) throws Exception {
        return MlScriptExtractor.extraerTrain(workDir);
    }

    Path extraerEmbeddingsScript(Path workDir) throws Exception {
        return MlScriptExtractor.extraerEmbeddings(workDir);
    }

    /**
     * Lanza entrenamiento ML en background después del scraping. Solo entrena si no existe modelo o
     * si el modelo tiene más de 24h de antigüedad.
     */
    public void entrenarEnBackground(boolean forceRetrain, boolean withImages, int epochs) {
        boolean useGpuSnapshot = this.useGpu;
        String python = detectarPython();
        if (python == null) { LOG.info("[ML-TRAIN] Python no disponible, saltando entrenamiento"); return; }
        if (!tieneDependenciasDeEntrenamiento(python)) {
            String msg = "Faltan numpy/scikit-learn/psycopg2 en " + python
                    + " — instalalas con scraper/ml-requirements.txt (Ejecutar_instalar.sh arma _tools/ml-venv)";
            LOG.warn("[ML-TRAIN] {}. Entrenamiento salteado.", msg);
            setTraining(new TrainingStatus(false, "skipped", 0, msg, null));
            return;
        }

        Path workDir = Paths.get("").toAbsolutePath();
        Path modelsDir = workDir.resolve("_models");
        Path textModel = modelsDir.resolve("text_classifier.pkl");
        boolean modelExists = Files.exists(textModel);

        if (forceRetrain) {
            LOG.info("[ML-TRAIN] forceRetrain=true — saltando verificación de antigüedad del modelo.");
        }

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
                setTraining(new TrainingStatus(true, "starting", 0, "",
                        java.time.Instant.now().toString()));

                Path trainScript = extraerTrainScript(workDir);

                SoporteTorch torch = detectarTorch(python, useGpuSnapshot);
                var cmd = comandoEntrenamiento(python, trainScript, withImages && torch.torch(), epochs);
                if (withImages && torch.torch()) {
                    LOG.info("[ML-TRAIN] PyTorch detectado (CUDA:{}) — entrenando texto + imágenes", torch.cuda());
                } else if (withImages) {
                    LOG.info("[ML-TRAIN] withImages=true pero PyTorch no disponible — solo texto");
                } else {
                    LOG.info("[ML-TRAIN] Solo entrenamiento de texto");
                }

                ProcessBuilder pb = construirProcessBuilderEntrenamiento(cmd, workDir, useGpuSnapshot);
                Process proc = pb.start();

                drenarEnHilo(proc.getErrorStream(), line -> LOG.info("[ML-TRAIN] {}", line));

                var sb = new StringBuilder();
                try (var br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append("\n");
                        if (line.startsWith("{") && line.contains("\"pct\"")) {
                            try {
                                var node = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readTree(line);
                                int pct  = node.path("pct").asInt();
                                String ph = node.path("phase").asText("");
                                String msg = node.path("msg").asText("");
                                LOG.info("[ML-TRAIN] [{}] {}% — {}", ph.toUpperCase(), pct, msg);
                                setTraining(new TrainingStatus(true, ph, pct, msg,
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
                    setTraining(new TrainingStatus(false, "timeout", 0, "", null));
                    return;
                }
                int exitCode = proc.exitValue();

                String out = sb.toString().trim();
                String finalJson = "";
                for (String l : out.split("\n")) {
                    if (l.startsWith("{") && l.contains("status")) finalJson = l;
                }
                if (exitCode == 0) {
                    LOG.info("[ML-TRAIN] ✓ ENTRENAMIENTO COMPLETADO");
                    if (!finalJson.isBlank()) LOG.info("[ML-TRAIN] Resultado: {}", finalJson);
                    LOG.info("[ML-TRAIN] Aplicando modelo a datos actuales...");
                    aplicarModeloActual();
                    setTraining(TrainingStatus.idle());
                } else {
                    LOG.warn("[ML-TRAIN] Proceso terminó con código {}", exitCode);
                    setTraining(new TrainingStatus(false, "error", 0, "exit " + exitCode, null));
                }
            } catch (Exception e) {
                LOG.warn("[ML-TRAIN] Error inesperado: {}", e.getMessage());
                setTraining(new TrainingStatus(false, "error", 0, e.getMessage(), null));
            }
        });
    }

    public void entrenarEnBackground(boolean forceRetrain) {
        entrenarEnBackground(forceRetrain, false, 8);
    }

    public void entrenarEnBackground() {
        entrenarEnBackground(false, false, 8);
    }

    /**
     * Test seam (package-private, pure): decides whether text re-training should be skipped for
     * freshness — mirrors the guard inlined in {@link #entrenarEnBackground}
     * ({@code modelExists && !forceRetrain && age < 24h}).
     */
    boolean debeSaltearEntrenamientoPorFrescura(boolean modelExists, boolean forceRetrain,
            long edadModeloMillis) {
        if (forceRetrain || !modelExists) return false;
        return edadModeloMillis < 24L * 3600 * 1000;
    }

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
     * Mirrors {@link #entrenarEnBackground} and {@link #backfillEmbeddingsEnBackground}'s
     * executor/status/progress pattern (same {@link TrainingStatus} record, same
     * {@code construirProcessBuilderEntrenamiento}/ {@code construirProcessBuilderBackfill} seams)
     * rather than reusing their thread bodies directly, so this new sequencing path can never
     * regress either standalone entrypoint.
     */
    public boolean construirIndiceVisualEnBackground(boolean forceRetrainTexto,
            boolean withImages, int epochs, boolean forceBackfillEmbeddings) {
        if (!intentarReservarSecuenciaIndiceVisual()) {
            LOG.info("[ML-INDEX] Secuencia de construcción de índice visual ya en curso — solicitud ignorada");
            return false;
        }

        boolean useGpuSnapshot = this.useGpu;
        String python = detectarPython();
        if (python == null) {
            LOG.info("[ML-INDEX] Python no disponible, saltando construcción de índice visual");
            setTraining(TrainingStatus.idle()); // libera la reserva — no hay hilo que la haga
            return true;
        }

        Path workDir = Paths.get("").toAbsolutePath();

        try {
            lanzarHiloSecuencia(() -> ejecutarSecuenciaIndiceVisual(python, workDir,
                    forceRetrainTexto, withImages, epochs, forceBackfillEmbeddings, useGpuSnapshot));
        } catch (Throwable t) {
            // RESI-001: no thread was started, so nothing will ever clear the reservation — release
            // it as a durable error (running=false) and let the failure propagate to the caller.
            LOG.error("[ML-INDEX] No se pudo iniciar el hilo de la secuencia: {}", t.getMessage());
            marcarFalloIndiceVisual("sequencing", "no se pudo iniciar el hilo: " + t.getMessage());
            throw t;
        }
        return true;
    }

    /**
     * Test seam (package-private): spawns the background thread for
     * {@link #construirIndiceVisualEnBackground}.
     */
    Thread lanzarHiloSecuencia(Runnable body) {
        return Thread.ofVirtual().start(body);
    }

    /**
     * Test seam / re-entrancy guard: atomically reserves the "sequence in flight" slot for
     * {@link #construirIndiceVisualEnBackground}.
     */
    boolean intentarReservarSecuenciaIndiceVisual() {
        TrainingStatus previo = trainingStatus.get();
        if (previo.running()) return false;
        TrainingStatus reservado = new TrainingStatus(true, "starting", 0, "", java.time.Instant.now().toString());
        if (!trainingStatus.compareAndSet(previo, reservado)) return false;
        publicar(reservado);
        return true;
    }

    /**
     * Test seam (package-private): the synchronous core of
     * {@link #construirIndiceVisualEnBackground} — same sequencing logic the public async
     * entrypoint delegates to (via {@code Thread.ofVirtual()}), extracted so a test can invoke it
     * directly with an explicit {@code python} executable (bypassing {@code detectarPython()}'s
     * real-environment scan) and assert on the final {@link #trainingStatus} without polling a
     * background thread.
     */
    void ejecutarSecuenciaIndiceVisual(String python, Path workDir,
            boolean forceRetrainTexto, boolean withImages, int epochs, boolean forceBackfillEmbeddings,
            boolean useGpuSnapshot) {
        boolean trainingOk = false;
        boolean backfillOk = false;
        try {
            setTraining(new TrainingStatus(true, "training", 0, "",
                    java.time.Instant.now().toString()));
            trainingOk = ejecutarFaseEntrenamientoSecuenciada(python, workDir, forceRetrainTexto,
                    withImages, epochs, useGpuSnapshot);

            if (trainingOk) {
                setTraining(new TrainingStatus(true, "embedding", 0, "",
                        trainingStatus.get().startedAt()));
            }
            backfillOk = ejecutarFaseBackfillSecuenciada(python, workDir, forceBackfillEmbeddings,
                    useGpuSnapshot);

            if (!trainingOk && backfillOk) {
                marcarFalloIndiceVisual("training",
                        "entrenamiento falló, backfill completado igual");
            }
        } catch (Throwable e) {
            LOG.warn("[ML-INDEX] Error inesperado en la secuencia de construcción de índice: {}",
                    e.getMessage());
            marcarFalloIndiceVisual("sequencing", e.getMessage());
        } finally {
            if (debeResetearAIdleTrasSecuencia(trainingOk, backfillOk)) {
                setTraining(TrainingStatus.idle());
            }
        }
    }

    public boolean construirIndiceVisualEnBackground(boolean forceRetrainTexto,
            boolean forceBackfillEmbeddings) {
        return construirIndiceVisualEnBackground(forceRetrainTexto, false, 8, forceBackfillEmbeddings);
    }

    record ResultadoEspera(boolean finished, int exitCode) {}

    /**
     * Drains {@code proc}'s stdout and stderr (via the optional {@code stderrLineHandler}) on
     * separate virtual threads, while THIS thread blocks ONLY on
     * {@code proc.waitFor(timeout, unit)} — never on a blocking stdout {@code readLine()} loop.
     */
    ResultadoEspera esperarConDrain(Process proc, long timeout, TimeUnit unit, String fase,
            java.util.function.Consumer<String> stdoutLineHandler,
            java.util.function.Consumer<String> stderrLineHandler) {
        Thread stdoutThread = drenarEnHilo(proc.getInputStream(), line -> {
            setTraining(parsearLineaProgreso(line, fase, trainingStatus.get()));
            if (stdoutLineHandler != null) stdoutLineHandler.accept(line);
        });
        Thread stderrThread = drenarEnHilo(proc.getErrorStream(), line -> {
            if (stderrLineHandler != null) stderrLineHandler.accept(line);
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

    private static Thread drenarEnHilo(InputStream in, java.util.function.Consumer<String> porLinea) {
        return Thread.ofVirtual().start(() -> {
            try (var br = new BufferedReader(new InputStreamReader(in))) {
                String line;
                while ((line = br.readLine()) != null) porLinea.accept(line);
            } catch (Exception ignored) {}
        });
    }

    /**
     * Bounded join for a stdout/stderr drain thread after {@code waitFor()} returns — the child's
     * streams close once the process exits, so the reader thread reaches EOF and finishes almost
     * immediately; this join is just to avoid racing the last couple of buffered lines before the
     * caller reads final counters.
     */
    private void joinDrainThread(Thread t) {
        try {
            t.join(java.time.Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Used by {@link #esBackfillDegradado} detection (RESI-002) — counted, never parsed further.
     */
    static final String BACKFILL_SIN_SENAL_MARKER = "no visual signal for";

    private static final java.util.regex.Pattern PROGRESO_PROCESADAS_PATTERN =
            java.util.regex.Pattern.compile("^(\\d+)/(\\d+) — ");

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
     * A backfill "succeeds" (exit 0) even when the model never loaded and every processed row hit
     * ml_embeddings.py's classify()-degrades-to-"" skip branch (logged via
     * {@link #BACKFILL_SIN_SENAL_MARKER} instead of persisted) — {@code ml_embeddings.py} always
     * {@code sys.exit(0)} for the {@code backfill} subcommand regardless.
     */
    boolean esBackfillDegradado(int filasProcesadas, int filasSinSenal) {
        return filasProcesadas > 0 && filasSinSenal >= filasProcesadas;
    }

    private void marcarFalloIndiceVisual(String fase, String motivo) {
        setTraining(new TrainingStatus(false, "error", 0,
                "[" + fase + "] " + (StringUtils.defaultString(motivo)), null));
    }

    boolean debeResetearAIdleTrasSecuencia(boolean trainingOk, boolean backfillOk) {
        return trainingOk && backfillOk;
    }

    boolean ejecutarFaseEntrenamientoSecuenciada(String python, Path workDir,
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
            boolean hasTorch = detectarTorch(python, useGpuSnapshot).torch();
            var cmd = comandoEntrenamiento(python, trainScript, withImages && hasTorch, epochs);

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

    boolean ejecutarFaseBackfillSecuenciada(String python, Path workDir,
            boolean force, boolean useGpuSnapshot) {
        try {
            Path scriptPath = extraerEmbeddingsScript(workDir);
            ProcessBuilder pb = construirProcessBuilderBackfill(
                    python, scriptPath.toString(), force, useGpuSnapshot);
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

    /** Nunca lanza excepciones — cualquier fallo degrada a un no-op logueado. */
    public void backfillEmbeddingsEnBackground(boolean force) {
        boolean useGpuSnapshot = this.useGpu;
        try {
            String python = detectarPython();
            if (python == null) {
                LOG.info("[ML-BACKFILL] Python no disponible, saltando backfill de embeddings");
                return;
            }

            Path workDir = Paths.get("").toAbsolutePath();

            Thread.ofVirtual().start(() -> {
                try {
                    setBackfill(new BackfillStatus(true, 0, "",
                            java.time.Instant.now().toString()));

                    Path scriptPath = extraerEmbeddingsScript(workDir);

                    ProcessBuilder pb = construirProcessBuilderBackfill(
                            python, scriptPath.toString(), force, useGpuSnapshot);
                    Process proc = pb.start();

                    var filasProcesadas = new java.util.concurrent.atomic.AtomicInteger(-1);
                    var filasSinSenal = new java.util.concurrent.atomic.AtomicInteger(0);

                    Thread stderrThread = drenarEnHilo(proc.getErrorStream(), line -> {
                        LOG.info("[ML-BACKFILL] {}", line);
                        if (line.contains(BACKFILL_SIN_SENAL_MARKER)) filasSinSenal.incrementAndGet();
                    });

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
                                    setBackfill(new BackfillStatus(true, pct, msg,
                                            backfillStatus.get().startedAt()));
                                    int procesadas = extraerProcesadasDeLineaProgreso(line);
                                    if (procesadas >= 0) filasProcesadas.set(procesadas);
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    // Full-catalog download + embedding pass, generous timeout — same as the
                    // `--images` training path (see entrenarEnBackground).
                    long timeoutMin = 180L;
                    boolean finished = proc.waitFor(timeoutMin, TimeUnit.MINUTES);
                    if (!finished) {
                        proc.destroyForcibly();
                        LOG.error("[ML-BACKFILL] TIMEOUT tras {} min — proceso terminado forzosamente", timeoutMin);
                        setBackfill(BackfillStatus.idle());
                        return;
                    }
                    // Brief bounded join so the stderr thread (racing the stdout loop above, which
                    // already blocked until the child's stdout closed at exit) has a moment to
                    // flush its last buffered lines before the degraded-row count below is read.
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
                    setBackfill(BackfillStatus.idle());
                } catch (Exception e) {
                    LOG.warn("[ML-BACKFILL] Error inesperado: {}", e.getMessage());
                    setBackfill(BackfillStatus.idle());
                }
            });
        } catch (Exception e) {
            LOG.warn("[ML-BACKFILL] Error inesperado al lanzar el backfill: {}", e.getMessage());
        }
    }

    public void backfillEmbeddingsEnBackground() {
        backfillEmbeddingsEnBackground(false);
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
     * The probes' {@code forceCpu} has the INVERSE polarity ({@code true} = force CPU on that probe
     * subprocess via {@code CUDA_VISIBLE_DEVICES=-1}), so this must return {@code !useGpuSnapshot}.
     */
    boolean forceCpuParaProbes(boolean useGpuSnapshot) {
        return !useGpuSnapshot;
    }

    private record SoporteTorch(boolean cuda, boolean torch) {}

    private SoporteTorch detectarTorch(String python, boolean useGpuSnapshot) {
        boolean forceCpuProbe = forceCpuParaProbes(useGpuSnapshot);
        boolean hasCuda = useGpuSnapshot && tieneCuda(python, forceCpuProbe);
        return new SoporteTorch(hasCuda, hasCuda || tienePytorch(python, forceCpuProbe));
    }

    private static java.util.List<String> comandoEntrenamiento(String python, Path trainScript,
            boolean conImagenes, int epochs) {
        var cmd = new java.util.ArrayList<String>(java.util.List.of(python, trainScript.toString()));
        if (conImagenes) cmd.addAll(java.util.List.of("--images", "--epochs", String.valueOf(epochs)));
        return cmd;
    }

    private boolean tienePytorch(String python, boolean forceCpu) {
        return probeResponde(python, "import torch; print('ok')", forceCpu);
    }

    private boolean tieneCuda(String python, boolean forceCpu) {
        return probeResponde(python, "import torch; print('ok' if torch.cuda.is_available() else 'no')",
                forceCpu);
    }

    private boolean probeResponde(String python, String codigoPython, boolean forceCpu) {
        try {
            Process proc = construirProcessBuilderProbe(python, codigoPython, forceCpu).start();
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return false;
            }
            return new String(proc.getInputStream().readAllBytes()).trim().contains("ok");
        } catch (Exception e) { return false; }
    }

    // ── Test seams (package-private): construyen los ProcessBuilder de cada subproceso Python SIN
    // iniciarlos, para poder verificar en tests el env var CUDA_VISIBLE_DEVICES sin depender de un
    // intérprete Python real. ──

    ProcessBuilder construirProcessBuilderScoring(String python, Path scriptPath, Path prodPath,
            Path outPath, Path histPath, Path workDir, boolean useGpuSnapshot) {
        ProcessBuilder pb = new ProcessBuilder(
                python, scriptPath.toString(),
                prodPath.toString(), outPath.toString(), histPath.toString());
        pb.redirectErrorStream(false);
        pb.directory(workDir.toFile());
        // UTF-8 evita el mojibake en los logs (estad�sticas → estadísticas) y PYTHONUNBUFFERED hace
        // que stderr se vacíe línea a línea, así un crash nativo (ej. exit 0xC0000409) no se traga
        // las últimas líneas y podemos ver dónde murió.
        return configurarEnv(pb, workDir, useGpuSnapshot, true);
    }

    private ProcessBuilder configurarEnv(ProcessBuilder pb, Path workDir, boolean useGpuSnapshot,
            boolean unbuffered) {
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");
        if (unbuffered) pb.environment().put("PYTHONUNBUFFERED", "1");
        aplicarEnvBaseDatosYModelos(pb, workDir);
        if (!useGpuSnapshot) {
            pb.environment().put("CUDA_VISIBLE_DEVICES", "-1");
        }
        return pb;
    }

    ProcessBuilder construirProcessBuilderEntrenamiento(java.util.List<String> cmd, Path workDir,
            boolean useGpuSnapshot) {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(false);
        return configurarEnv(pb, workDir, useGpuSnapshot, false);
    }

    ProcessBuilder construirProcessBuilderBackfill(String python, String scriptPath,
            boolean force, boolean useGpuSnapshot) {
        var cmd = new java.util.ArrayList<String>();
        cmd.add(python);
        cmd.add(scriptPath);
        cmd.add("backfill");
        if (force) cmd.add("--force");
        if (!useGpuSnapshot) cmd.add("--no-gpu");

        Path workDir = Paths.get("").toAbsolutePath();
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(false);
        return configurarEnv(pb, workDir, useGpuSnapshot, true);
    }

    /**
     * {@code DATABASE_URL} is only set when present in THIS process's own environment (never
     * clobbers with a literal null); it would already be inherited by the child via
     * {@code ProcessBuilder}'s environment-copy default, but setting it explicitly keeps the
     * contract visible and directly testable here rather than implicit.
     */

    void aplicarEnvBaseDatosYModelos(ProcessBuilder pb, Path workDir) {
        PythonEnv.aplicar(pb, workDir);
    }

    /**
     * Test seam (package-private, pure): translates the JVM's own {@code DATABASE_URL} (JDBC
     * format) into a libpq/psycopg2-compatible DSN for the Python subprocess env — libpq only
     * recognizes the {@code postgresql://}/{@code postgres://} schemes, not {@code jdbc:}.
     */
    static String toPsycopgDsn(String jdbcOrPlainUrl, String username, String password) {
        return PythonEnv.toPsycopgDsn(jdbcOrPlainUrl, username, password);
    }

    static String resolveModelsRoot(String envModelsRoot, Path workDir) {
        return PythonEnv.resolveModelsRoot(envModelsRoot, workDir);
    }

    /**
     * {@code <modelsRoot>/marqo} — same shape the installer pins and {@code ml_embeddings.py}'s own
     * fallback.
     */
    static String hfHomeParaModelsRoot(String modelsRoot) {
        return PythonEnv.hfHomeParaModelsRoot(modelsRoot);
    }

    ProcessBuilder construirProcessBuilderProbe(String python, String codigoPython, boolean forceCpu) {
        ProcessBuilder pb = new ProcessBuilder(python, "-c", codigoPython)
                .redirectErrorStream(true);
        if (forceCpu) {
            pb.environment().put("CUDA_VISIBLE_DEVICES", "-1");
        }
        return pb;
    }

    static java.util.List<String> candidatosLocales(String wd) {
        return java.util.List.of(
            wd + "/../_tools/python/python.exe",
            wd + "/_tools/python/python.exe",
            wd + "/../_tools/python/python3",
            wd + "/../_tools/ml-venv/bin/python",
            wd + "/_tools/ml-venv/bin/python");
    }

    /** {@code ml_train.py} needs these; scoring runs on a bare python3, so finding one proves nothing. */
    boolean tieneDependenciasDeEntrenamiento(String python) {
        return probeResponde(python, "import numpy, sklearn, psycopg2; print('ok')", true);
    }

    String detectarPython() {
        String sysPy = System.getProperty("PYTHON_EXE");
        if (StringUtils.isNotBlank(sysPy) && new java.io.File(sysPy).exists()) return sysPy;

        for (String path : candidatosLocales(System.getProperty("user.dir"))) {
            if (new java.io.File(path).exists()) return path;
        }

        for (String cmd : new String[]{"python3","python"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return null;
    }
}
