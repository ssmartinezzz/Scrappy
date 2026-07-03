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

    /**
     * Ejecuta el pipeline ML con contrato de 3 estados:
     * - NOT_RUN (Python no encontrado, timeout, exit!=0, sin output, excepción) → retorna {@code null}
     * - EMPTY/VALID (proceso OK, output parseado tal cual) → retorna el {@link JsonNode} leído
     *
     * La validación de contenido (scores/tendencias válidos) NO es responsabilidad de este
     * método — vive en {@code DatabaseService} y {@code ApiController}.
     */
    public JsonNode ejecutar(String productosJson) {
        var stderrTail = new java.util.concurrent.ConcurrentLinkedDeque<String>();
        try {
            Path workDir   = Paths.get("").toAbsolutePath();
            Path prodPath  = workDir.resolve("ml_productos.json");
            Path outPath   = workDir.resolve("ml_output.json");
            Path histPath  = workDir.resolve("precio_historico.json");
            Path scriptPath = extraerScript(workDir);

            Files.writeString(prodPath, productosJson);

            String python = detectarPython();
            if (python == null) {
                LOG.error("[ML] Python no encontrado — pipeline ML NO ejecutado (NOT_RUN)");
                return null;
            }

            LOG.info("[ML] Ejecutando pipeline Python...");
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

    /**
     * Lanza entrenamiento ML en background después del scraping.
     * Solo entrena si no existe modelo o si el modelo tiene más de 24h de antigüedad.
     * Con forceRetrain=true, saltea la verificación de antigüedad y siempre entrena.
     */
    public void entrenarEnBackground(String dbPath, boolean forceRetrain,
                                      boolean withImages, int epochs) {
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

                boolean hasCuda  = tieneCuda(python);
                boolean hasTorch = hasCuda || tienePytorch(python);
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

                ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(workDir.toFile())
                    .redirectErrorStream(false);
                pb.environment().put("PYTHONIOENCODING", "utf-8");
                pb.environment().put("PYTHONUTF8", "1");
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

    private boolean tienePytorch(String python) {
        try {
            var pb = new ProcessBuilder(python, "-c", "import torch; print('ok')")
                    .redirectErrorStream(true);
            Process proc = pb.start();
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return false;
            }
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            return out.contains("ok");
        } catch (Exception e) { return false; }
    }

    private boolean tieneCuda(String python) {
        try {
            var pb = new ProcessBuilder(python, "-c",
                    "import torch; print('ok' if torch.cuda.is_available() else 'no')")
                    .redirectErrorStream(true);
            Process proc = pb.start();
            if (!proc.waitFor(10, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return false;
            }
            String out = new String(proc.getInputStream().readAllBytes()).trim();
            return out.contains("ok");
        } catch (Exception e) { return false; }
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
