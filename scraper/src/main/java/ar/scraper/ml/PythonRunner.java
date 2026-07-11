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

                    // Stderr: logs [ML-BACKFILL]
                    Thread.ofVirtual().start(() -> {
                        try (var br = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                            String line;
                            while ((line = br.readLine()) != null) LOG.info("[ML-BACKFILL] {}", line);
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

                    int exitCode = proc.exitValue();
                    if (exitCode == 0) {
                        LOG.info("[ML-BACKFILL] ✓ BACKFILL COMPLETADO");
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
        if (!useGpuSnapshot) {
            pb.environment().put("CUDA_VISIBLE_DEVICES", "-1");
        }
        return pb;
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
