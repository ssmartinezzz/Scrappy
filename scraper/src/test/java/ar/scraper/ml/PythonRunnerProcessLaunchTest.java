package ar.scraper.ml;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-process coverage of every PythonRunner launch path, driven by a shell stand-in for Python
 * (the Windows-only tests in this package cover the same seams with cmd.exe and are skipped on
 * Linux). Pins the log prefixes, the probe wiring and the stderr tail.
 */
@Epic("ML Pipeline")
@Feature("Python Runner")
@Story("Subprocess launch, drain and probes")
@DisplayName("PythonRunner — launch, stderr drain and probes against a fake interpreter")
@DisabledOnOs(OS.WINDOWS)
class PythonRunnerProcessLaunchTest {

    private final PythonRunner runner = new PythonRunner();
    private final Logger logger = (Logger) LoggerFactory.getLogger(PythonRunner.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private String pythonExeAntes;
    private byte[] productosAntes;
    private Path productos;

    @TempDir
    Path dir;

    @BeforeEach
    void attach() throws Exception {
        appender.start();
        logger.addAppender(appender);
        pythonExeAntes = System.getProperty("PYTHON_EXE");
        productos = Paths.get("").toAbsolutePath().resolve("ml_productos.json");
        productosAntes = Files.exists(productos) ? Files.readAllBytes(productos) : null;
    }

    @AfterEach
    void detach() throws Exception {
        logger.detachAppender(appender);
        if (pythonExeAntes == null) System.clearProperty("PYTHON_EXE");
        else System.setProperty("PYTHON_EXE", pythonExeAntes);
        if (productosAntes != null) Files.write(productos, productosAntes);
        else Files.deleteIfExists(productos);
    }

    private List<String> mensajes() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private ILoggingEvent evento(String prefijo) {
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith(prefijo))
                .findFirst().orElseThrow(() -> new AssertionError(prefijo + " not logged: " + mensajes()));
    }

    private void esperar(BooleanSupplier cond) throws Exception {
        long limite = System.nanoTime() + 20_000_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > limite) throw new AssertionError("timed out: " + mensajes());
            Thread.sleep(25);
        }
    }

    /** Probes print {@code torch}/{@code cuda}; a forced-CPU probe never sees CUDA. */
    private Path fakePython(String torch, String cuda, String comportamiento) throws Exception {
        Path script = dir.resolve("fake_python.sh");
        Files.writeString(script, """
                #!/bin/sh
                if [ "$1" = "-c" ]; then
                  case "$2" in
                    *cuda*) if [ "$CUDA_VISIBLE_DEVICES" = "-1" ]; then echo no; else echo %s; fi ;;
                    *) echo %s ;;
                  esac
                  exit 0
                fi
                echo "$@" > "%s"
                env > "%s"
                %s
                """.formatted(cuda, torch, argv(), entorno(), comportamiento));
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private Path argv() { return dir.resolve("argv.txt"); }

    private Path entorno() { return dir.resolve("env.txt"); }

    private String argvRegistrado() throws Exception { return Files.readString(argv()).trim(); }

    @Test
    @DisplayName("sequenced training adds --images --epochs when the torch probe answers ok")
    void trainingAddsImagesWhenTorchProbeOk() throws Exception {
        Path py = fakePython("ok", "no", "exit 0");

        boolean ok = runner.ejecutarFaseEntrenamientoSecuenciada(
                py.toString(), dir, true, true, 3, true);

        assertThat(ok).isTrue();
        assertThat(argvRegistrado()).endsWith("ml_train.py --images --epochs 3");
    }

    @Test
    @DisplayName("sequenced training stays text-only when neither probe finds torch or cuda")
    void trainingTextOnlyWhenProbesSayNo() throws Exception {
        Path py = fakePython("no", "no", "exit 0");

        runner.ejecutarFaseEntrenamientoSecuenciada(py.toString(), dir, true, true, 3, true);

        assertThat(argvRegistrado()).endsWith("ml_train.py").doesNotContain("--images");
    }

    @Test
    @DisplayName("a CUDA hit counts as torch even when the torch probe says no")
    void cudaImpliesTorch() throws Exception {
        Path py = fakePython("no", "ok", "exit 0");

        runner.ejecutarFaseEntrenamientoSecuenciada(py.toString(), dir, true, true, 3, true);

        assertThat(argvRegistrado()).contains("--images --epochs 3");
    }

    @Test
    @DisplayName("with the GPU disabled the probes run forced to CPU, so CUDA is never seen")
    void gpuDisabledForcesCpuOnProbes() throws Exception {
        Path py = fakePython("no", "ok", "exit 0");

        runner.ejecutarFaseEntrenamientoSecuenciada(py.toString(), dir, true, true, 3, false);

        assertThat(argvRegistrado()).doesNotContain("--images");
        assertThat(Files.readString(entorno())).contains("CUDA_VISIBLE_DEVICES=-1");
    }

    @Test
    @DisplayName("a probe whose interpreter cannot be launched answers false")
    void probeFailureMeansNoImages() throws Exception {
        boolean ok = runner.ejecutarFaseEntrenamientoSecuenciada(
                dir.resolve("missing").toString(), dir, true, true, 3, true);

        assertThat(ok).isFalse();
        assertThat(mensajes()).anyMatch(m -> m.startsWith("[ML-INDEX] [TRAINING] Error inesperado"));
    }

    @Test
    @DisplayName("sequenced training logs stderr with the [ML-INDEX] [TRAINING] prefix and reports the exit code")
    void sequencedTrainingLogsStderr() throws Exception {
        Path py = fakePython("no", "no", "echo hola >&2\nexit 3");

        boolean ok = runner.ejecutarFaseEntrenamientoSecuenciada(py.toString(), dir, true, false, 8, true);

        assertThat(ok).isFalse();
        assertThat(mensajes()).contains("[ML-INDEX] [TRAINING] hola",
                "[ML-INDEX] [TRAINING] Proceso terminó con código 3");
        assertThat(evento("[ML-INDEX] [TRAINING] Proceso").getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    @DisplayName("sequenced backfill logs stderr with the [ML-INDEX] [EMBEDDING] prefix and flags a degraded run")
    void sequencedBackfillFlagsDegraded() throws Exception {
        Path py = fakePython("no", "no", """
                echo '{"pct":10,"phase":"embed","msg":"4/4 — x"}'
                echo "no visual signal for a" >&2
                echo "no visual signal for b" >&2
                echo "no visual signal for c" >&2
                echo "no visual signal for d" >&2
                exit 0""");

        boolean ok = runner.ejecutarFaseBackfillSecuenciada(py.toString(), dir, true, false);

        assertThat(ok).isFalse();
        assertThat(mensajes()).contains("[ML-INDEX] [EMBEDDING] no visual signal for a",
                "[ML-INDEX] [EMBEDDING] backfill degradado — modelo no disponible, 4 de 4 filas sin señal visual");
        assertThat(argvRegistrado()).endsWith("ml_embeddings.py backfill --force --no-gpu");
        assertThat(runner.getTrainingStatus().msg()).isEqualTo("[embedding] degradado: modelo no disponible");
    }

    @Test
    @DisplayName("scoring logs stderr as [ML] lines and keeps only the last 50 in the failure tail")
    void scoringTailKeepsLastFiftyLines() throws Exception {
        String ecos = String.join("\n", IntStream.rangeClosed(1, 60)
                .mapToObj(i -> "echo linea" + i + " >&2").toList());
        System.setProperty("PYTHON_EXE", fakePython("no", "no", ecos + "\nsleep 1\nexit 3").toString());

        assertThat(runner.ejecutar("[]")).isNull();

        assertThat(mensajes()).contains("[ML] Ejecutando pipeline Python...", "[ML] linea1", "[ML] linea60");
        ILoggingEvent fallo = evento("[ML] Pipeline exit code 3");
        assertThat(fallo.getLevel()).isEqualTo(Level.ERROR);
        String cola = fallo.getFormattedMessage();
        assertThat(cola).startsWith("[ML] Pipeline exit code 3 — NOT_RUN. stderr tail:\nlinea11\n")
                .endsWith("\nlinea60")
                .doesNotContain("linea10\n");
    }

    @Test
    @DisplayName("scoring sets the unbuffered UTF-8 env on the child")
    void scoringChildEnv() throws Exception {
        System.setProperty("PYTHON_EXE", fakePython("no", "no", "exit 3").toString());

        runner.ejecutar("[]");

        assertThat(Files.readString(entorno()))
                .contains("PYTHONIOENCODING=utf-8", "PYTHONUTF8=1", "PYTHONUNBUFFERED=1");
    }

    @Test
    @DisplayName("background training logs stderr and progress as [ML-TRAIN] and ends in an error status on a bad exit")
    void backgroundTrainingLogsAndFails() throws Exception {
        System.setProperty("PYTHON_EXE", fakePython("no", "no", """
                echo hola >&2
                echo '{"pct":50,"phase":"fit","msg":"mitad"}'
                sleep 1
                exit 3""").toString());

        runner.entrenarEnBackground(true, false, 8);
        esperar(() -> mensajes().contains("[ML-TRAIN] Proceso terminó con código 3"));

        assertThat(mensajes()).contains("[ML-TRAIN] hola", "[ML-TRAIN] [FIT] 50% — mitad",
                "[ML-TRAIN] Solo entrenamiento de texto");
        esperar(() -> "error".equals(runner.getTrainingStatus().phase()));
        assertThat(runner.getTrainingStatus().msg()).isEqualTo("exit 3");
        assertThat(Files.readString(entorno())).doesNotContain("PYTHONUNBUFFERED");
    }

    @Test
    @DisplayName("background training with images logs the PyTorch detection and passes --images --epochs")
    void backgroundTrainingWithImages() throws Exception {
        System.setProperty("PYTHON_EXE", fakePython("ok", "ok", "sleep 1\nexit 3").toString());

        runner.entrenarEnBackground(true, true, 5);
        esperar(() -> mensajes().contains("[ML-TRAIN] Proceso terminó con código 3"));

        assertThat(mensajes()).contains(
                "[ML-TRAIN] PyTorch detectado (CUDA:true) — entrenando texto + imágenes");
        assertThat(argvRegistrado()).endsWith("ml_train.py --images --epochs 5");
    }

    @Test
    @DisplayName("background backfill logs stderr and progress as [ML-BACKFILL] and flags a degraded run")
    void backgroundBackfillLogsAndFlagsDegraded() throws Exception {
        System.setProperty("PYTHON_EXE", fakePython("no", "no", """
                echo '{"pct":10,"phase":"embed","msg":"2/2 — x"}'
                echo "no visual signal for a" >&2
                echo "no visual signal for b" >&2
                exit 0""").toString());

        runner.backfillEmbeddingsEnBackground(false);
        esperar(() -> mensajes().stream().anyMatch(m -> m.startsWith("[ML-BACKFILL] backfill degradado")));

        assertThat(mensajes()).contains("[ML-BACKFILL] no visual signal for a",
                "[ML-BACKFILL] [EMBED] 10% — 2/2 — x",
                "[ML-BACKFILL] backfill degradado — modelo no disponible, 2 de 2 filas sin señal visual");
        assertThat(argvRegistrado()).endsWith("ml_embeddings.py backfill");
        assertThat(Files.readString(entorno())).contains("PYTHONUNBUFFERED=1");
    }
}
